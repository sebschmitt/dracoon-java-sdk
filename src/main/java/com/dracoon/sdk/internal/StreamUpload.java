package com.dracoon.sdk.internal;

import java.io.IOException;
import java.io.OutputStream;

import com.dracoon.sdk.Log;
import com.dracoon.sdk.error.DracoonApiCode;
import com.dracoon.sdk.error.DracoonApiException;
import com.dracoon.sdk.error.DracoonException;
import com.dracoon.sdk.error.DracoonNetIOException;
import com.dracoon.sdk.internal.model.ApiCompleteFileUploadRequest;
import com.dracoon.sdk.internal.model.ApiCreateFileUploadRequest;
import com.dracoon.sdk.internal.model.ApiExpiration;
import com.dracoon.sdk.internal.model.ApiFileUpload;
import com.dracoon.sdk.internal.model.ApiNode;
import com.dracoon.sdk.model.FileUploadRequest;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Response;

public class StreamUpload extends OutputStream {

    private static final String LOG_TAG = StreamUpload.class.getSimpleName();

    private static final int CHUNK_SIZE = 256 * 1024;

    private final DracoonClientImpl mClient;
    private final Log mLog;
    private final DracoonService mRestService;
    private final DracoonErrorParser mErrorParser;
    private final HttpHelper mHttpHelper;

    private final FileUploadRequest mFileUploadRequest;

    private String mUploadId;

    private byte[] mChunk = new byte[CHUNK_SIZE];
    private long mChunkNum = 0L;
    private int mChunkOffset = 0;
    private boolean mIsClosed = false;

    StreamUpload(DracoonClientImpl client, FileUploadRequest request) throws DracoonNetIOException,
            DracoonApiException {
        mClient = client;
        mLog = client.getLog();
        mRestService = client.getDracoonService();
        mErrorParser = client.getDracoonErrorParser();
        mHttpHelper = client.getHttpHelper();

        mFileUploadRequest = request;

        init();
    }

    private void init() throws DracoonNetIOException, DracoonApiException {
        mUploadId = createUpload();
    }

    @Override
    public void write(int b) throws IOException {
        byte[] ba = new byte[1];
        ba[0] = (byte) b;
        write(ba);
    }

    @Override
    public void write(byte b[], int off, int len) throws IOException {
        assertNotClosed();

        // If start offset and/or maximum number of bytes is invalid: Throw error
        if ((off | len | (off + len) | (b.length - (off + len))) < 0) {
            throw new IndexOutOfBoundsException();
        }

        // If no bytes should be read: Abort
        if (len == 0) {
            return;
        }

        // Read while maximum number of bytes is reached
        int read = 0;
        while (read < len) {
            // Calculate current total offset and total remaining bytes
            long totalOffset = mChunkNum * CHUNK_SIZE + mChunkOffset;

            // Calculate number of bytes which should be copied
            int count = len - read;
            if (count > CHUNK_SIZE - mChunkOffset) {
                count = CHUNK_SIZE - mChunkOffset;
            }

            mLog.d(LOG_TAG, String.format("Loading: %d: %d-%d (%d-%d)", mChunkNum,
                    mChunkOffset, mChunkOffset + count, totalOffset, totalOffset + count));

            // Copy bytes
            System.arraycopy(b, off + read, mChunk, mChunkOffset, count);

            // Update chunk offset
            mChunkOffset = mChunkOffset + count;

            // If end of current chunk was reached: Load next chunk
            if (mChunkOffset == CHUNK_SIZE) {
                try {
                    loadNextChunk();
                } catch (DracoonException e) {
                    throw new IOException("Could not write to upload stream.", e);
                }
                mChunkNum++;
                mChunkOffset = 0;
            }

            // Update read count
            read = read + count;
        }
    }

    @Override
    public void close() throws IOException {
        assertNotClosed();

        try {
            loadNextChunk();
        } catch (DracoonException e) {
            throw new IOException("Could not write to upload stream.", e);
        }

        try {
            completeUpload();
        } catch (DracoonException e) {
            throw new IOException("Could not close upload stream.", e);
        }

        mChunk = null;
        mIsClosed = true;
    }

    // --- Helper methods ---

    private String createUpload() throws DracoonNetIOException, DracoonApiException {
        String auth = mClient.buildAuthString();

        ApiCreateFileUploadRequest request = new ApiCreateFileUploadRequest();
        request.parentId = mFileUploadRequest.getParentId();
        request.name = mFileUploadRequest.getName();
        request.classification = mFileUploadRequest.getClassification().getValue();
        request.notes = mFileUploadRequest.getNotes();
        if (mFileUploadRequest.getExpirationDate() != null) {
            ApiExpiration apiExpiration = new ApiExpiration();
            apiExpiration.enableExpiration = mFileUploadRequest.getExpirationDate().getTime() != 0L;
            apiExpiration.expireAt = mFileUploadRequest.getExpirationDate();
            request.expiration = apiExpiration;
        }

        Call<ApiFileUpload> call = mRestService.createFileUpload(auth, request);
        Response<ApiFileUpload> response = mHttpHelper.executeRequest(call);

        if (!response.isSuccessful()) {
            DracoonApiCode errorCode = mErrorParser.parseFileUploadCreateError(response);
            String errorText = String.format("Creation of upload stream for file '%s' failed " +
                    "with '%s'!", mFileUploadRequest.getName(), errorCode.name());
            mLog.d(LOG_TAG, errorText);
            throw new DracoonApiException(errorCode);
        }

        return response.body().uploadId;
    }

    private void assertNotClosed() throws IOException {
        if (mIsClosed) {
            throw new IOException("Stream was already closed.");
        }
    }

    private void loadNextChunk() throws DracoonNetIOException, DracoonApiException {
        if (mChunkOffset <= 0) {
            return;
        }

        long offset = mChunkNum * CHUNK_SIZE;

        String auth = mClient.buildAuthString();

        RequestBody requestBody = RequestBody.create(MediaType.parse("application/octet-stream"),
                mChunk, 0, mChunkOffset);
        MultipartBody.Part body = MultipartBody.Part.createFormData("file",
                mFileUploadRequest.getName(), requestBody);

        String contentRange = "bytes " + offset + "-" + (offset + mChunkOffset) + "/*";

        Call<Void> call = mRestService.uploadFile(auth, mUploadId, contentRange, body);
        Response<Void> response = mHttpHelper.executeRequest(call);

        if (!response.isSuccessful()) {
            DracoonApiCode errorCode = mErrorParser.parseFileUploadError(response);
            String errorText = String.format("Upload of file '%s' failed with '%s'!",
                    mFileUploadRequest.getName(), errorCode.name());
            mLog.d(LOG_TAG, errorText);
            throw new DracoonApiException(errorCode);
        }
    }

    private void completeUpload() throws DracoonNetIOException, DracoonApiException {
        String auth = mClient.buildAuthString();

        ApiCompleteFileUploadRequest request = new ApiCompleteFileUploadRequest();
        request.fileName = mFileUploadRequest.getName();
        request.resolutionStrategy = mFileUploadRequest.getResolutionStrategy().getValue();

        Call<ApiNode> call = mRestService.completeFileUpload(auth, mUploadId, request);
        Response<ApiNode> response = mHttpHelper.executeRequest(call);

        if (!response.isSuccessful()) {
            DracoonApiCode errorCode = mErrorParser.parseFileUploadCompleteError(response);
            String errorText = String.format("Closing of upload stream for file '%s' failed " +
                            "with '%s'!", mFileUploadRequest.getName(), errorCode.name());
            mLog.d(LOG_TAG, errorText);
            throw new DracoonApiException(errorCode);
        }
    }

}