package com.dosbox.emu;

import android.util.Log;
import android.view.KeyEvent;

import fi.iki.elonen.NanoHTTPD;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.UUID;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * WiFi Controller WebSocket Server
 * Relays controller input through Cloudflare WebSocket.
 */
public class WifiControllerServer extends NanoHTTPD {
    private static final String TAG = "WifiControllerServer";
    private static final String WS_TAG = "WebSocketController";
    private static final int PORT = 8080;
    private static final long RECONNECT_DELAY_MS = 2000L;
    
    private final ControllerEventListener listener;
    private final OkHttpClient relayClient;
    private final String relayWsBaseUrl;
    private final String sessionId;

    private volatile boolean isRunning = true;
    private volatile WebSocket relaySocket;
    private final android.os.Handler reconnectHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    
    /**
     * Listener interface for controller events
     */
    public interface ControllerEventListener {
        void onControllerKeyEvent(int keyCode, boolean pressed);
        void onControllerMouseMove(int dx, int dy);
        void onControllerMouseButton(int button, boolean pressed);
        void onTrackpadEnd(); // Called when user lifts finger from trackpad
        void onControllerJoystick(float x, float y, long timestamp); // Called with joystick position (-1.0 to 1.0) and timestamp
        void onControllerTextLine(String text);
    }

    public WifiControllerServer(ControllerEventListener listener, String relayWsBaseUrl) throws IOException {
        super(PORT);
        this.listener = listener;
        this.relayWsBaseUrl = relayWsBaseUrl;
        this.sessionId = UUID.randomUUID().toString().replace("-", "");
        this.relayClient = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        try {
            this.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.i(TAG, "=================================");
            Log.i(TAG, "WifiControllerServer SUCCESSFULLY bound to port " + PORT);
            Log.i(TAG, "Local controller page serving is disabled");
            Log.i(TAG, "Session: " + sessionId);
            Log.i(TAG, "Connecting relay socket to: " + getCarRelayUrl());
            Log.i(TAG, "=================================");
            connectRelay();
        } catch (IOException e) {
            Log.e(TAG, "=================================");
            Log.e(TAG, "WifiControllerServer FAILED to bind to port " + PORT + ":" + e.getMessage(), e);
            Log.e(TAG, "=================================");
            throw e;
        }
    }

    public String buildHostedControllerUrl(String controllerWebBaseUrl) {
        String base = (controllerWebBaseUrl == null || controllerWebBaseUrl.trim().isEmpty())
            ? "https://www.code-odyssey.com/controller.html"
                : controllerWebBaseUrl.trim();
        String encodedWs;
        try {
            encodedWs = URLEncoder.encode(relayWsBaseUrl, "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "Failed to URL-encode relay URL, using raw value", e);
            encodedWs = relayWsBaseUrl;
        }
        return base + "?session=" + sessionId + "&ws=" + encodedWs;
    }

    private String getCarRelayUrl() {
        return relayWsBaseUrl + "?session=" + sessionId + "&role=car";
    }

    private void connectRelay() {
        if (!isRunning) {
            return;
        }

        String wsUrl = getCarRelayUrl();
        Request request = new Request.Builder().url(wsUrl).build();
        relaySocket = relayClient.newWebSocket(request, new RelayWebSocketListener());
    }

    private void scheduleReconnect() {
        if (!isRunning) {
            return;
        }
        reconnectHandler.removeCallbacksAndMessages(null);
        reconnectHandler.postDelayed(this::connectRelay, RECONNECT_DELAY_MS);
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> headers = session.getHeaders();
        String upgrade = headers.get("upgrade");
        
        Log.d(TAG, "======== HTTP REQUEST RECEIVED ========");
        Log.d(TAG, "URI: " + uri);
        Log.d(TAG, "From: " + session.getRemoteIpAddress());
        Log.d(TAG, "Method: " + session.getMethod());
        Log.d(TAG, "Upgrade header: " + upgrade);
        Log.d(TAG, "All headers: " + headers.toString());
        Log.d(TAG, "========================================");

        if ("websocket".equalsIgnoreCase(upgrade)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Expected Cloud relay WebSocket");
        }

        if ("/".equals(uri) || "/controller.html".equals(uri) || "/index.html".equals(uri)) {
            return newFixedLengthResponse(
                Response.Status.GONE,
                "text/plain",
                "Local controller page disabled. Use hosted controller URL from QR code."
            );
        }
        
        // 404 for other paths
        Log.w(TAG, "404 Not Found: " + uri);
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "text/plain",
            "Not Found"
        );
    }

    @Override
    public void stop() {
        isRunning = false;
        reconnectHandler.removeCallbacksAndMessages(null);

        if (relaySocket != null) {
            try {
                relaySocket.close(1000, "Controller stopped");
            } catch (Exception e) {
                Log.w(WS_TAG, "Failed closing relay socket", e);
            }
            relaySocket = null;
        }

        relayClient.dispatcher().executorService().shutdown();
        relayClient.connectionPool().evictAll();
        super.stop();
    }

    private class RelayWebSocketListener extends WebSocketListener {
        public void onOpen(WebSocket webSocket, Response response) {
            Log.i(WS_TAG, "Cloud relay socket connected");
            JSONObject connect = new JSONObject();
            try {
                connect.put("type", "CONNECT");
                connect.put("timestamp", System.currentTimeMillis());
                webSocket.send(connect.toString());
            } catch (JSONException e) {
                Log.w(WS_TAG, "Failed to send connect message", e);
            }
        }

        public void onMessage(WebSocket webSocket, String text) {
            handleRelayMessage(text);
        }

        public void onMessage(WebSocket webSocket, ByteString bytes) {
            handleRelayMessage(bytes.utf8());
        }

        public void onClosing(WebSocket webSocket, int code, String reason) {
            Log.w(WS_TAG, "Cloud relay closing: code=" + code + " reason=" + reason);
            webSocket.close(1000, null);
            scheduleReconnect();
        }

        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.w(WS_TAG, "Cloud relay closed: code=" + code + " reason=" + reason);
            scheduleReconnect();
        }

        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.e(WS_TAG, "Cloud relay socket failure", t);
            scheduleReconnect();
        }
    }

    private void handleRelayMessage(String payload) {
        try {
            Object parsed = new org.json.JSONTokener(payload).nextValue();
            if (parsed instanceof JSONArray) {
                JSONArray array = (JSONArray) parsed;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item != null) {
                        dispatchControllerEvent(item);
                    }
                }
                return;
            }

            if (parsed instanceof JSONObject) {
                dispatchControllerEvent((JSONObject) parsed);
                return;
            }

            Log.w(WS_TAG, "Ignoring unsupported relay payload");
        } catch (Exception e) {
            Log.e(WS_TAG, "Failed parsing relay message: " + payload, e);
        }
    }

    private void dispatchControllerEvent(JSONObject json) throws JSONException {
        String type = json.optString("type", "");
        if (type.isEmpty()) {
            return;
        }

        if ("CONNECT".equals(type)) {
            Log.i(WS_TAG, "Controller connected through relay");
            return;
        }

        if ("MOUSE".equals(type)) {
            handleMouseMove(json);
            return;
        }

        if ("KEY".equals(type)) {
            handleKeyEvent(json);
            return;
        }

        if ("MOUSE_BUTTON".equals(type)) {
            handleMouseButton(json);
            return;
        }

        if ("TRACKPAD_END".equals(type)) {
            handleTrackpadEnd();
            return;
        }

        if ("JOYSTICK".equals(type)) {
            handleJoystick(json);
            return;
        }

        if ("TEXT_LINE".equals(type)) {
            handleTextLine(json);
            return;
        }

        Log.w(WS_TAG, "Unknown controller message type: " + type);
    }

    private void handleKeyEvent(JSONObject json) {
        int webKeyCode = json.optInt("code", -1);
        String action = json.optString("action", "");
        int androidKeyCode = mapWebKeyToAndroid(webKeyCode);
        boolean pressed = "DOWN".equals(action);

        if (listener != null) {
            listener.onControllerKeyEvent(androidKeyCode, pressed);
        }
    }

    private void handleMouseMove(JSONObject json) {
        int dx = json.optInt("x", 0);
        int dy = json.optInt("y", 0);
        if (listener != null) {
            listener.onControllerMouseMove(dx, dy);
        }
    }

    private void handleTrackpadEnd() {
        if (listener != null) {
            listener.onTrackpadEnd();
        }
    }

    private void handleJoystick(JSONObject json) {
        double x = json.optDouble("x", 0.0);
        double y = json.optDouble("y", 0.0);
        long timestamp = json.optLong("timestamp", System.currentTimeMillis());
        if (listener != null) {
            listener.onControllerJoystick((float) x, (float) y, timestamp);
        }
    }

    private void handleMouseButton(JSONObject json) {
        int button = json.optInt("button", 1);
        String action = json.optString("action", "");
        boolean pressed = "DOWN".equals(action);
        if (listener != null) {
            listener.onControllerMouseButton(button, pressed);
        }
    }

    private void handleTextLine(JSONObject json) {
        String text = json.optString("text", "").trim();
        if (!text.isEmpty() && listener != null) {
            listener.onControllerTextLine(text);
        }
    }

    private int mapWebKeyToAndroid(int webKeyCode) {
        if (webKeyCode >= 65 && webKeyCode <= 90) {
            return KeyEvent.KEYCODE_A + (webKeyCode - 65);
        }

        if (webKeyCode >= 48 && webKeyCode <= 57) {
            return KeyEvent.KEYCODE_0 + (webKeyCode - 48);
        }

        if (webKeyCode >= 112 && webKeyCode <= 123) {
            return KeyEvent.KEYCODE_F1 + (webKeyCode - 112);
        }

        switch (webKeyCode) {
            case 8: return KeyEvent.KEYCODE_DEL;
            case 9: return KeyEvent.KEYCODE_TAB;
            case 13: return KeyEvent.KEYCODE_ENTER;
            case 16: return KeyEvent.KEYCODE_SHIFT_LEFT;
            case 17: return KeyEvent.KEYCODE_CTRL_LEFT;
            case 18: return KeyEvent.KEYCODE_ALT_LEFT;
            case 27: return KeyEvent.KEYCODE_ESCAPE;
            case 32: return KeyEvent.KEYCODE_SPACE;
            case 37: return KeyEvent.KEYCODE_DPAD_LEFT;
            case 38: return KeyEvent.KEYCODE_DPAD_UP;
            case 39: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 40: return KeyEvent.KEYCODE_DPAD_DOWN;
            case 96: return KeyEvent.KEYCODE_NUMPAD_0;
            case 97: return KeyEvent.KEYCODE_NUMPAD_1;
            case 98: return KeyEvent.KEYCODE_NUMPAD_2;
            case 99: return KeyEvent.KEYCODE_NUMPAD_3;
            case 100: return KeyEvent.KEYCODE_NUMPAD_4;
            case 101: return KeyEvent.KEYCODE_NUMPAD_5;
            case 102: return KeyEvent.KEYCODE_NUMPAD_6;
            case 103: return KeyEvent.KEYCODE_NUMPAD_7;
            case 104: return KeyEvent.KEYCODE_NUMPAD_8;
            case 105: return KeyEvent.KEYCODE_NUMPAD_9;
            case 186: return KeyEvent.KEYCODE_SEMICOLON;
            case 187: return KeyEvent.KEYCODE_EQUALS;
            case 188: return KeyEvent.KEYCODE_COMMA;
            case 189: return KeyEvent.KEYCODE_MINUS;
            case 190: return KeyEvent.KEYCODE_PERIOD;
            case 191: return KeyEvent.KEYCODE_SLASH;
            case 192: return KeyEvent.KEYCODE_GRAVE;
            case 219: return KeyEvent.KEYCODE_LEFT_BRACKET;
            case 220: return KeyEvent.KEYCODE_BACKSLASH;
            case 221: return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case 222: return KeyEvent.KEYCODE_APOSTROPHE;
            case 33: return KeyEvent.KEYCODE_PAGE_UP;
            case 34: return KeyEvent.KEYCODE_PAGE_DOWN;
            case 35: return KeyEvent.KEYCODE_MOVE_END;
            case 36: return KeyEvent.KEYCODE_MOVE_HOME;
            case 45: return KeyEvent.KEYCODE_INSERT;
            case 46: return KeyEvent.KEYCODE_FORWARD_DEL;
            default:
                Log.w(TAG, "Unmapped key code: " + webKeyCode);
                return KeyEvent.KEYCODE_UNKNOWN;
        }
    }
}
