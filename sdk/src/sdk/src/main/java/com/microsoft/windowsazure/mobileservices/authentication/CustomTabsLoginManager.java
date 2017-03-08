/*
Copyright (c) Microsoft Open Technologies, Inc.
All Rights Reserved
Apache 2.0 License

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

See the Apache Version 2.0 License for specific language governing permissions and limitations under the License.
 */

package com.microsoft.windowsazure.mobileservices.authentication;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import com.google.gson.Gson;
import com.microsoft.windowsazure.mobileservices.MobileServiceClient;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.net.URL;

/**
 * Class for handling Login operations with Authentication Providers
 * using Chrome Custom Tabs. When Custom Tabs are not available,
 * a regular browser will be used.
 * The {@link #authenticate} method abstract
 * login implementation. The login implementation involves {@link CustomTabsIntermediateActivity}
 * and {@link CustomTabsLoginActivity}.
 */
public class CustomTabsLoginManager {

    static final String KEY_LOGIN_IN_PROGRESS = "LoginInProgress";
    static final String KEY_LOGIN_USER_ID = "UserId";
    static final String KEY_LOGIN_AUTHENTICATION_TOKEN = "AuthenticationToken";
    static final String KEY_LOGIN_STATE = "LoginState";
    static final String KEY_LOGIN_INTENT = "LoginIntent";
    static final String KEY_LOGIN_ERROR = "error";

    private static final String EASY_AUTH_LOGIN_URL_PATH = ".auth/login";
    private static final String EASY_AUTH_CALLBACK_URL_PATH = "easyauth.callback";
    private static final String EASY_AUTH_LOGIN_PARAM_REDIRECT_URL = "post_login_redirect_url";
    private static final String EASY_AUTH_LOGIN_PARAM_CODE_CHALLENGE = "code_challenge";
    private static final String EASY_AUTH_LOGIN_PARAM_CODE_CHALLENGE_METHOD = "code_challenge_method";
    private static final String EASY_AUTH_LOGIN_PARAM_CODE_VERIFIER = "code_verifier";
    private static final String EASY_AUTH_LOGIN_PARAM_AUTHORIZATION_CODE = "authorization_code";

    private static final int CODE_VERIFIER_ENTROPY = 32;
    private static final int MIN_CODE_VERIFIER_LENGTH = 43;
    private static final String CODE_CHALLENGE_METHOD_SHA256 = "S256";

    private String mAppUrl;

    private String mLoginUriPrefix;

    private String mAlternateLoginHost;

    private CustomTabsClientHelper mCustomTabsClientHelper;

    private boolean mDisposed = false;


    public CustomTabsLoginManager(Context context, String appUrl, String loginUriPrefix, String alternateLoginHost) {
        mCustomTabsClientHelper = new CustomTabsClientHelper(context);
        mAppUrl = appUrl;
        mLoginUriPrefix = loginUriPrefix;
        mAlternateLoginHost = alternateLoginHost;
    }

    // Retrieve MobileServiceUser from intent data
    public static MobileServiceUser getMobileServiceUserFromLoginResult(Intent data) {
        MobileServiceUser user = null;

        String userId = data.getStringExtra(KEY_LOGIN_USER_ID);
        String authenticationToken = data.getStringExtra(KEY_LOGIN_AUTHENTICATION_TOKEN);

        if (userId != null && authenticationToken != null) {
            user = new MobileServiceUser(userId);
            user.setAuthenticationToken(authenticationToken);
        }

        return user;
    }

    public void authenticate(String provider, String uriScheme, HashMap<String, String> parameters, Context context, int authRequestCode) {
        provider = UriHelper.normalizeProvider(provider);
        String codeVerifier = generateRandomCodeVerifier();
        CustomTabsLoginState loginState = new CustomTabsLoginState(uriScheme, codeVerifier, provider, mAppUrl, mLoginUriPrefix, mAlternateLoginHost);

        Uri loginUri = this.buildLoginUri(provider, uriScheme, parameters, codeVerifier);

        this.startCustomTabsIntermediateActivityForResult(context, loginUri, loginState, authRequestCode);
    }

    private Uri buildLoginUri(String provider, String uriScheme, HashMap<String, String> parameters, String codeVerifier) {
        if (uriScheme == null || codeVerifier == null) {
            return null;
        }

        String loginUrl = buildUrlPath(provider, mAppUrl, mLoginUriPrefix, mAlternateLoginHost);

        String redirectURLString = uriScheme + "://" + EASY_AUTH_CALLBACK_URL_PATH;

        String codeChallenge = sha256Base64Encode(codeVerifier);
        if (parameters == null) {
            parameters = new HashMap<>();
        }
        parameters.put(EASY_AUTH_LOGIN_PARAM_REDIRECT_URL, redirectURLString);
        parameters.put(EASY_AUTH_LOGIN_PARAM_CODE_CHALLENGE, codeChallenge);
        parameters.put(EASY_AUTH_LOGIN_PARAM_CODE_CHALLENGE_METHOD, CODE_CHALLENGE_METHOD_SHA256);

        // set session_mode to token to work with AppServiceAuthSession cookie
        // when opened in Chrome Custom Tabs
        parameters.put("session_mode", "token");

        String queryString = UriHelper.normalizeAndUrlEncodeParameters(parameters, MobileServiceClient.UTF8_ENCODING);

        loginUrl = loginUrl + queryString;

        return Uri.parse(loginUrl);
    }

    private void startCustomTabsIntermediateActivityForResult(Context context, Uri loginUri, CustomTabsLoginState loginState, int authRequestCode) {
        String loginStateJson = new Gson().toJson(loginState);

        Intent i = new Intent(context, CustomTabsIntermediateActivity.class);
        i.putExtra(KEY_LOGIN_INTENT, mCustomTabsClientHelper.createLoginIntent(loginUri));
        i.putExtra(KEY_LOGIN_STATE, loginStateJson);

        ((Activity) context).startActivityForResult(i, authRequestCode);
    }

    static boolean isRedirectURLValid(Uri redirectUrl, String uriScheme) {
        if (redirectUrl != null && redirectUrl.getScheme() != null
                && uriScheme != null && redirectUrl.getScheme().toLowerCase().equals(uriScheme.toLowerCase())) {
            // Redirect URL matches url scheme
            if (redirectUrl.getHost() != null && redirectUrl.getHost().equals(EASY_AUTH_CALLBACK_URL_PATH)) {
                // Redirect URL matches url path
                return true;
            }
        }

        return false;
    }

    static String authorizationCodeFromRedirectUrl(Uri redirectUrl) {

        String authorizationCodeQuery = "#" + EASY_AUTH_LOGIN_PARAM_AUTHORIZATION_CODE + "=";

        String authorizationCode = null;
        if (redirectUrl != null) {
            int index = redirectUrl.toString().indexOf(authorizationCodeQuery);
            if (index >= 0) {
                authorizationCode = redirectUrl.toString().substring(index + authorizationCodeQuery.length());
                try {
                    authorizationCode = URLDecoder.decode(authorizationCode, MobileServiceClient.UTF8_ENCODING);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                    return null;
                }
            }
        }

        return authorizationCode;
    }

    private static String buildUrlPath(String provider, String appUrl, String loginUriPrefix, String alternateLoginHost) {
        String path;

        if (loginUriPrefix != null) {
            path = UriHelper.CombinePath(loginUriPrefix, provider);
        } else {
            path = UriHelper.CombinePath(EASY_AUTH_LOGIN_URL_PATH, provider);
        }

        String url = "";
        if (alternateLoginHost != null) {
            url = UriHelper.CombinePath(alternateLoginHost, path);
        } else {
            try {
                url = UriHelper.CombinePath(UriHelper.createHostOnlyUrl(new URL(appUrl)).toString(), path);
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }
        return url;
    }

    static String buildCodeExchangeUrl(CustomTabsLoginState loginState, String authorizationCode) {
        String urlPath = buildUrlPath(loginState.getAuthenticationProvider(), loginState.getAppUrl(), loginState.getLoginUriPrefix(), loginState.getAlternateLoginHost());

        String codeExchangeUrlPath = UriHelper.CombinePath(urlPath, "token");

        HashMap<String, String> parameters = new HashMap<>();
        parameters.put(EASY_AUTH_LOGIN_PARAM_AUTHORIZATION_CODE, authorizationCode);
        parameters.put(EASY_AUTH_LOGIN_PARAM_CODE_VERIFIER, loginState.getCodeVerifier());

        String queryString = UriHelper.normalizeAndUrlEncodeParameters(parameters, MobileServiceClient.UTF8_ENCODING);

        return codeExchangeUrlPath + queryString;
    }

    // SHA-256 hashing followed by Base64 encoding of the string input
    private static String sha256Base64Encode(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }

        MessageDigest md;

        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }

        md.update(input.getBytes());
        byte[] digest = md.digest();

        return Base64.encodeToString(digest, Base64.NO_WRAP);
    }

    private static String generateRandomCodeVerifier() {
        byte[] randomBytes = new byte[CODE_VERIFIER_ENTROPY];
        new SecureRandom().nextBytes(randomBytes);
        String base64String = Base64.encodeToString(randomBytes, Base64.NO_WRAP);

        if (base64String.length() < MIN_CODE_VERIFIER_LENGTH) {
            throw new IllegalArgumentException("Code verifier length is shorter than minimal allowed PKCE specification.");
        }

        return base64String;
    }

    /**
     * Dispose (unbind) custom tabs service when login service is no longer required.
     */
    public void dispose() {
        if (mDisposed) {
            return;
        }
        mCustomTabsClientHelper.unbindCustomTabsService();
        mDisposed = true;
    }
}