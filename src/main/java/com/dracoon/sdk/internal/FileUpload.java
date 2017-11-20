package com.dracoon.sdk.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.dracoon.sdk.error.DracoonApiCode;
import com.dracoon.sdk.error.DracoonApiException;
import com.dracoon.sdk.error.DracoonException;
import com.dracoon.sdk.error.DracoonFileIOException;
import com.dracoon.sdk.internal.mapper.NodeMapper;
import com.dracoon.sdk.internal.model.ApiCompleteFileUploadRequest;
import com.dracoon.sdk.internal.model.ApiCreateFileUploadRequest;
import com.dracoon.sdk.internal.model.ApiFileUpload;
import com.dracoon.sdk.internal.model.ApiNode;
import com.dracoon.sdk.model.FileUploadCallback;
import com.dracoon.sdk.model.FileUploadRequest;
import com.dracoon.sdk.model.Node;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okio.BufferedSink;
import retrofit2.Call;
import retrofit2.Response;

public class FileUpload extends Thread {

    private static final String LOG_TAG = FileUpload.class.getSimpleName();

    private static final int JUNK_SIZE = 2 * 1024 * 1024;
    private static final int BLOCK_SIZE = 2 * 1024;
    private static final int PROGRESS_UPDATE_INTERVAL = 100;

    private static class FileRequestBody extends RequestBody {

        interface Callback {
            void onProgress(long send);
        }

        private final byte[] mData;
        private final int mLength;

        private Callback mCallback;

        FileRequestBody(byte[] data, int length) {
            mData = data;
            mLength = length;
        }

        void setCallback(Callback callback) {
            mCallback = callback;
        }

        @Override
        public MediaType contentType() {
            return MediaType.parse("application/octet-stream");
        }

        @Override
        public long contentLength() throws IOException {
            return mLength;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            int offset = 0;
            int remaining;
            while ((remaining = mLength - offset) > 0) {
                int count = remaining >= BLOCK_SIZE ? BLOCK_SIZE : remaining;
                sink.write(mData, offset, count);

                offset = offset + count;

                if (mCallback != null) {
                    mCallback.onProgress(offset);
                }
            }
        }

    }

    private final DracoonClientImpl mClient;
    private final DracoonService mService;

    private final String mId;
    private final FileUploadRequest mRequest;
    private final InputStream mSrcStream;
    private final long mSrcLength;

    private long mProgressUpdateTime = System.currentTimeMillis();

    private final List<FileUploadCallback> mCallbacks = new ArrayList<>();

    public FileUpload(DracoonClientImpl client, String id, FileUploadRequest request,
            InputStream srcStream, long srcLength) {
        mClient = client;
        mService = client.getDracoonService();

        mId = id;
        mRequest = request;
        mSrcStream = srcStream;
        mSrcLength = srcLength;
    }

    public void addCallback(FileUploadCallback callback) {
        if (callback != null) {
            mCallbacks.add(callback);
        }
    }

    public void removeCallback(FileUploadCallback callback) {
        if (callback != null) {
            mCallbacks.remove(callback);
        }
    }

    @Override
    public void run() {
        try {
            upload();
        } catch (InterruptedException e) {
            notifyCanceled(mId);
        } catch (DracoonException e) {
            notifyFailed(mId, e);
        }
    }

    public Node runSync() throws DracoonException {
        try {
            return upload();
        } catch (InterruptedException e) {
            notifyCanceled(mId);
            return null;
        } catch (DracoonException e) {
            notifyFailed(mId, e);
            throw e;
        }
    }

    private Node upload() throws DracoonException, InterruptedException {
        notifyStarted(mId);

        String uploadId = createUpload(mRequest.getParentId(), mRequest.getName(),
                mRequest.getClassification().getValue());

        uploadFile(uploadId, mRequest.getName(), mSrcStream, mSrcLength);

        ApiNode apiNode = completeUpload(uploadId, mRequest.getName());

        Node node = NodeMapper.fromApi(apiNode);

        notifyFinished(mId, node);

        return node;
    }

    private String createUpload(long parentNodeId, String name, int classification)
            throws DracoonException, InterruptedException {
        String authToken = mClient.getAccessToken();

        ApiCreateFileUploadRequest createRequest = new ApiCreateFileUploadRequest();
        createRequest.parentId = parentNodeId;
        createRequest.name = name;
        createRequest.classification = classification;

        Call<ApiFileUpload> call = mService.createFileUpload(authToken, createRequest);
        Response<ApiFileUpload> response = DracoonHttpHelper.executeRequest(call, this);

        if (!response.isSuccessful()) {
            DracoonApiCode errorCode = DracoonErrorParser.parseCreateFileUploadError(response);
            String errorText = String.format("Creation of file upload '%s' failed with '%s'!", mId,
                    errorCode.name());
            Log.d(LOG_TAG, errorText);
            throw new DracoonApiException(errorCode);
        }

        return response.body().uploadId;
    }

    private void uploadFile(String uploadId, String fileName, InputStream is, long length)
            throws DracoonException, InterruptedException {
        try {
            byte[] buffer = new byte[JUNK_SIZE];
            long offset = 0;
            int count;
            while ((count = is.read(buffer)) != -1) {
                uploadFileChunk(uploadId, fileName, buffer, offset, count, length);
                offset = offset + count;
            }
        } catch (IOException e) {
            if (isInterrupted()) {
                throw new InterruptedException();
            }
            String errorText = "File read failed!";
            Log.d(LOG_TAG, errorText);
            throw new DracoonFileIOException(errorText, e);
        }
    }

    private void uploadFileChunk(String uploadId, String fileName, byte[] data, long offset,
            int count, long length) throws DracoonException, InterruptedException {
        String authToken = mClient.getAccessToken();

        FileRequestBody requestBody = new FileRequestBody(data, count);
        requestBody.setCallback(send -> {
            if (mProgressUpdateTime + PROGRESS_UPDATE_INTERVAL < System.currentTimeMillis()
                    && !isInterrupted()) {
                notifyRunning(mId, offset + send, length);
                mProgressUpdateTime = System.currentTimeMillis();
            }
        });
        MultipartBody.Part body = MultipartBody.Part.createFormData("file", fileName, requestBody);

        String contentRange = "bytes " + offset + "-" + (offset + count) + "/*";

        Call<Void> call = mService.uploadFile(authToken, uploadId, contentRange, body);
        Response<Void> response = DracoonHttpHelper.executeRequest(call, this);

        if (!response.isSuccessful()) {
            DracoonApiCode errorCode = DracoonErrorParser.parseFileUploadError(response);
            String errorText = String.format("Upload of file upload '%s' failed with '%s'!", mId,
                    errorCode.name());
            Log.d(LOG_TAG, errorText);
            throw new DracoonApiException(errorCode);
        }
    }

    private ApiNode completeUpload(String uploadId, String fileName) throws DracoonException,
            InterruptedException {
        String authToken = mClient.getAccessToken();

        ApiCompleteFileUploadRequest request = new ApiCompleteFileUploadRequest();
        request.fileName = fileName;

        Call<ApiNode> call = mService.completeFileUpload(authToken, uploadId, request);
        Response<ApiNode> response = DracoonHttpHelper.executeRequest(call, this);

        if (!response.isSuccessful()) {
            DracoonApiCode errorCode = DracoonErrorParser.parseCompleteFileUploadError(response);
            String errorText = String.format("Upload of file upload '%s' failed with '%s'!", mId,
                    errorCode.name());
            Log.d(LOG_TAG, errorText);
            throw new DracoonApiException(errorCode);
        }

        return response.body();
    }

    // --- Callback helper methods ---

    private void notifyStarted(String id) {
        mCallbacks.forEach(callback -> callback.onStarted(id));
    }

    private void notifyRunning(String id, long bytesSend, long bytesTotal) {
        mCallbacks.forEach(callback -> callback.onRunning(id, bytesSend, bytesTotal));
    }

    private void notifyFinished(String id, Node node) {
        mCallbacks.forEach(callback -> callback.onFinished(id, node));
    }

    private void notifyCanceled(String id) {
        mCallbacks.forEach(callback -> callback.onCanceled(id));
    }

    private void notifyFailed(String id, DracoonException e) {
        mCallbacks.forEach(callback -> callback.onFailed(id, e));
    }

}