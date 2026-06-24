package rm.phonepc.android;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final int TCP_PORT = 5555;
    private static final int UDP_PORT = 5556;
    private static final int SAMPLE_RATE = 8000;
    private static final int BUFFER_SIZE = 640;

    private EditText hostField;
    private EditText nickField;
    private TextView chatView;
    private TextView usersView;
    private EditText messageField;
    private Button connectButton;
    private Button voiceButton;
    private Button sendButton;

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String host;
    private String nick;
    private volatile boolean connected = false;
    private volatile boolean voiceOn = false;
    private DatagramSocket udpSocket;
    private InetAddress serverAddress;
    private AudioTrack audioTrack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        requestAudioPermission();
        buildUi();
    }

    private void requestAudioPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 100);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 18, 18, 18);
        root.setBackgroundColor(Color.rgb(49, 51, 56));

        TextView title = new TextView(this);
        title.setText("Phone ↔ PC Messenger");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(0, 12, 0, 8);

        hostField = new EditText(this);
        hostField.setHint("PC IP");
        hostField.setText("192.168.1.2");
        styleEdit(hostField);
        top.addView(hostField, new LinearLayout.LayoutParams(0, -2, 1));

        nickField = new EditText(this);
        nickField.setHint("Nick");
        nickField.setText("Phone" + (int)(Math.random() * 999));
        styleEdit(nickField);
        top.addView(nickField, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top);

        connectButton = new Button(this);
        connectButton.setText("Connect");
        connectButton.setOnClickListener(v -> connectOrDisconnect());
        root.addView(connectButton, new LinearLayout.LayoutParams(-1, -2));

        chatView = new TextView(this);
        chatView.setTextColor(Color.WHITE);
        chatView.setTextSize(14);
        chatView.setPadding(12, 12, 12, 12);
        chatView.setBackgroundColor(Color.rgb(30, 31, 34));
        chatView.setMovementMethod(new ScrollingMovementMethod());
        root.addView(chatView, new LinearLayout.LayoutParams(-1, 0, 1));

        usersView = new TextView(this);
        usersView.setTextColor(Color.rgb(185, 187, 190));
        usersView.setText("Online: -");
        usersView.setPadding(0, 8, 0, 8);
        root.addView(usersView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        messageField = new EditText(this);
        messageField.setHint("Message or /w nick message");
        styleEdit(messageField);
        bottom.addView(messageField, new LinearLayout.LayoutParams(0, -2, 1));

        sendButton = new Button(this);
        sendButton.setText("Send");
        sendButton.setOnClickListener(v -> sendMessage());
        bottom.addView(sendButton, new LinearLayout.LayoutParams(-2, -2));
        root.addView(bottom);

        voiceButton = new Button(this);
        voiceButton.setText("Join voice");
        voiceButton.setOnClickListener(v -> toggleVoice());
        root.addView(voiceButton, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private void styleEdit(EditText e) {
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(185, 187, 190));
        e.setSingleLine(true);
        e.setBackgroundColor(Color.rgb(30, 31, 34));
        e.setPadding(12, 8, 12, 8);
    }

    private void connectOrDisconnect() {
        if (connected) {
            disconnect();
        } else {
            connect();
        }
    }

    private void connect() {
        host = hostField.getText().toString().trim();
        nick = nickField.getText().toString().trim();
        if (host.isEmpty() || nick.isEmpty()) {
            toast("Enter PC IP and nickname");
            return;
        }
        new Thread(() -> {
            try {
                socket = new Socket(host, TCP_PORT);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                out.println("HELLO\t" + nick);
                connected = true;
                serverAddress = InetAddress.getByName(host);
                runOnUiThread(() -> {
                    connectButton.setText("Disconnect");
                    append("SYSTEM", "Connected to " + host);
                });
                readLoop();
            } catch (Exception e) {
                runOnUiThread(() -> append("ERROR", e.getMessage()));
                disconnect();
            }
        }, "tcp-client").start();
    }

    private void readLoop() {
        try {
            String line;
            while (connected && (line = in.readLine()) != null) {
                handleServerLine(line);
            }
        } catch (IOException ignored) {
        } finally {
            disconnect();
        }
    }

    private void handleServerLine(String line) {
        String[] p = line.split("\t", 4);
        String type = p[0];
        if ("MSG".equals(type) && p.length >= 4) {
            runOnUiThread(() -> append(p[1], p[3]));
        } else if ("PM".equals(type) && p.length >= 4) {
            runOnUiThread(() -> append("PM " + p[1], p[3]));
        } else if ("SYSTEM".equals(type) && p.length >= 3) {
            runOnUiThread(() -> append("SYSTEM", p[p.length - 1]));
        } else if ("USERS".equals(type)) {
            String users = p.length >= 2 ? p[1] : "";
            runOnUiThread(() -> usersView.setText("Online: " + users));
        } else if ("ERR".equals(type)) {
            runOnUiThread(() -> append("ERROR", p.length >= 2 ? p[1] : "Unknown error"));
        } else if ("OK".equals(type)) {
            runOnUiThread(() -> append("SYSTEM", p.length >= 2 ? p[1] : "OK"));
        }
    }

    private void sendMessage() {
        if (!connected || out == null) {
            toast("Not connected");
            return;
        }
        String text = messageField.getText().toString().trim();
        if (text.isEmpty()) return;
        messageField.setText("");
        if (text.startsWith("/w ")) {
            String[] parts = text.split(" ", 3);
            if (parts.length < 3) {
                append("SYSTEM", "Usage: /w nick message");
                return;
            }
            out.println("PM\t" + parts[1] + "\t" + parts[2]);
        } else {
            out.println("MSG\t" + text);
        }
    }

    private void toggleVoice() {
        if (!connected) {
            toast("Connect first");
            return;
        }
        if (!voiceOn) {
            voiceOn = true;
            voiceButton.setText("Leave voice");
            out.println("VOICE_JOIN");
            startVoice();
        } else {
            voiceOn = false;
            voiceButton.setText("Join voice");
            if (out != null) out.println("VOICE_LEAVE");
            if (udpSocket != null) udpSocket.close();
        }
    }

    private void startVoice() {
        new Thread(() -> {
            try {
                udpSocket = new DatagramSocket();
                initAudioTrack();
                sendVoicePacket(new byte[0], 0); // register UDP address
                startReceiverThread();
                startRecorderLoop();
            } catch (Exception e) {
                runOnUiThread(() -> append("VOICE ERROR", e.getMessage()));
                voiceOn = false;
                runOnUiThread(() -> voiceButton.setText("Join voice"));
            }
        }, "voice-main").start();
    }

    private void initAudioTrack() {
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SAMPLE_RATE), AudioTrack.MODE_STREAM);
        audioTrack.play();
    }

    private void startReceiverThread() {
        new Thread(() -> {
            byte[] buffer = new byte[2048];
            while (voiceOn && udpSocket != null && !udpSocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(packet);
                    playVoicePacket(packet.getData(), packet.getLength());
                } catch (IOException ignored) {
                    break;
                }
            }
        }, "voice-receiver").start();
    }

    private void startRecorderLoop() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread(() -> toast("Microphone permission is required"));
            return;
        }
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SAMPLE_RATE));
        byte[] audio = new byte[BUFFER_SIZE];
        try {
            record.startRecording();
            while (voiceOn && udpSocket != null && !udpSocket.isClosed()) {
                int read = record.read(audio, 0, audio.length);
                if (read > 0) sendVoicePacket(audio, read);
            }
        } finally {
            record.stop();
            record.release();
        }
    }

    private void sendVoicePacket(byte[] audio, int len) {
        try {
            byte[] header = (nick + "\n").getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[header.length + len];
            System.arraycopy(header, 0, payload, 0, header.length);
            if (len > 0) System.arraycopy(audio, 0, payload, header.length, len);
            DatagramPacket p = new DatagramPacket(payload, payload.length, serverAddress, UDP_PORT);
            udpSocket.send(p);
        } catch (IOException ignored) {
        }
    }

    private void playVoicePacket(byte[] data, int len) {
        int nl = -1;
        for (int i = 0; i < len; i++) {
            if (data[i] == '\n') { nl = i; break; }
        }
        if (nl <= 0) return;
        String sender = new String(data, 0, nl, StandardCharsets.UTF_8).trim();
        if (sender.equals(nick)) return;
        int offset = nl + 1;
        int audioLen = len - offset;
        if (audioLen > 0 && audioTrack != null) {
            audioTrack.write(data, offset, audioLen);
        }
    }

    private void disconnect() {
        connected = false;
        voiceOn = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
        runOnUiThread(() -> {
            connectButton.setText("Connect");
            voiceButton.setText("Join voice");
            append("SYSTEM", "Disconnected");
        });
    }

    private void append(String from, String text) {
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        chatView.append("[" + time + "] " + from + ": " + text + "\n");
        final int scrollAmount = chatView.getLayout() == null ? 0 : chatView.getLayout().getLineTop(chatView.getLineCount()) - chatView.getHeight();
        if (scrollAmount > 0) chatView.scrollTo(0, scrollAmount); else chatView.scrollTo(0, 0);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
