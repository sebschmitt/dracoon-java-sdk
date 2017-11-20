package com.dracoon.sdk.internal;

import com.dracoon.sdk.error.DracoonException;
import com.dracoon.sdk.error.DracoonNetIOException;
import com.dracoon.sdk.error.DracoonNetInsecureException;
import retrofit2.Call;
import retrofit2.Response;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;

public class DracoonHttpHelper {

    private static final String LOG_TAG = DracoonHttpHelper.class.getSimpleName();

    private DracoonHttpHelper() {}

    // --- Methods for REST calls ---

    @SuppressWarnings("unchecked")
    public static <T> Response<T> executeRequest(Call<T> call) throws DracoonException {
        Response response;
        try {
            response = call.execute();
        } catch (SSLHandshakeException e) {
            String errorText = "Server SSL handshake failed!";
            Log.e(LOG_TAG, errorText, e);
            throw new DracoonNetInsecureException(errorText, e);
        } catch (IOException e) {
            String errorText = "Server communication failed!";
            Log.d(LOG_TAG, errorText);
            throw new DracoonNetIOException(errorText, e);
        }

        return (Response<T>) response;
    }

    @SuppressWarnings("unchecked")
    public static <T> Response<T> executeRequest(Call<T> call, Thread thread)
            throws DracoonException, InterruptedException {
        Response response;
        try {
            response = executeRequest(call);
        } catch (DracoonNetIOException e) {
            if (thread.isInterrupted()) {
                throw new InterruptedException();
            }
            throw e;
        }

        return (Response<T>) response;
    }

    // --- Methods for HTTP calls ---

    public static okhttp3.Response executeRequest(okhttp3.Call call) throws DracoonException {
        try {
            return call.execute();
        } catch (SSLHandshakeException e) {
            String errorText = "Server SSL handshake failed!";
            Log.e(LOG_TAG, errorText, e);
            throw new DracoonNetInsecureException(errorText, e);
        } catch (IOException e) {
            String errorText = "Server communication failed!";
            Log.d(LOG_TAG, errorText);
            throw new DracoonNetIOException(errorText, e);
        }
    }

    public static okhttp3.Response executeRequest(okhttp3.Call call, Thread thread)
            throws DracoonException, InterruptedException {
        try {
            return executeRequest(call);
        } catch (DracoonNetIOException e) {
            if (thread.isInterrupted()) {
                throw new InterruptedException();
            }
            throw e;
        }
    }

}