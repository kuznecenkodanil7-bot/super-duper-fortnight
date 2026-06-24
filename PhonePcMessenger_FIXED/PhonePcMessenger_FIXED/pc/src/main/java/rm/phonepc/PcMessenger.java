package rm.phonepc;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class PcMessenger extends JFrame {
    private static final int TCP_PORT = 5555;
    private static final int UDP_PORT = 5556;
    private static final int SAMPLE_RATE = 8000;
    private static final int VOICE_BUFFER = 640;

    private final JTextArea chatArea = new JTextArea();
    private final JTextField input = new JTextField();
    private final DefaultListModel<String> usersModel = new DefaultListModel<>();
    private final JButton voiceButton = new JButton("Join voice");
    private final JLabel statusLabel = new JLabel("Starting...");

    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Map<String, InetSocketAddress> voiceAddresses = new ConcurrentHashMap<>();

    private volatile boolean running = true;
    private volatile boolean pcVoice = false;
    private DatagramSocket voiceSocket;
    private SourceDataLine speaker;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PcMessenger().setVisible(true));
    }

    public PcMessenger() {
        super("Phone ↔ PC Messenger");
        setupUi();
        startServer();
        startVoiceUdp();
    }

    private void setupUi() {
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(900, 560);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        root.setBackground(new Color(49, 51, 56));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        JLabel title = new JLabel("PC Messenger Server");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        statusLabel.setForeground(new Color(185, 187, 190));
        top.add(title, BorderLayout.WEST);
        top.add(statusLabel, BorderLayout.EAST);

        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setBackground(new Color(30, 31, 34));
        chatArea.setForeground(Color.WHITE);
        chatArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setBorder(BorderFactory.createLineBorder(new Color(64, 66, 73)));

        JList<String> users = new JList<>(usersModel);
        users.setBackground(new Color(43, 45, 49));
        users.setForeground(Color.WHITE);
        users.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        JScrollPane usersScroll = new JScrollPane(users);
        usersScroll.setPreferredSize(new Dimension(180, 0));
        usersScroll.setBorder(BorderFactory.createTitledBorder("Online phones"));

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.setOpaque(false);
        input.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        JButton send = new JButton("Send");
        voiceButton.addActionListener(e -> togglePcVoice());
        send.addActionListener(e -> sendPcMessage());
        input.addActionListener(e -> sendPcMessage());
        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 6));
        buttons.setOpaque(false);
        buttons.add(send);
        buttons.add(voiceButton);
        bottom.add(input, BorderLayout.CENTER);
        bottom.add(buttons, BorderLayout.EAST);

        root.add(top, BorderLayout.NORTH);
        root.add(chatScroll, BorderLayout.CENTER);
        root.add(usersScroll, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);

        append("SYSTEM", "Start this app on PC. On phone enter this IP: " + getLocalIp() + " TCP " + TCP_PORT + " UDP " + UDP_PORT);
    }

    private void startServer() {
        Thread t = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("IP " + getLocalIp() + " : " + TCP_PORT));
                append("SYSTEM", "TCP chat server started on port " + TCP_PORT);
                while (running) {
                    Socket socket = serverSocket.accept();
                    Thread handler = new Thread(new ClientHandler(socket), "client-handler");
                    handler.setDaemon(true);
                    handler.start();
                }
            } catch (IOException ex) {
                append("ERROR", "TCP server error: " + ex.getMessage());
            }
        }, "tcp-server");
        t.setDaemon(true);
        t.start();
    }

    private void startVoiceUdp() {
        Thread t = new Thread(() -> {
            try {
                voiceSocket = new DatagramSocket(UDP_PORT);
                initSpeaker();
                append("SYSTEM", "UDP voice server started on port " + UDP_PORT);
                byte[] buffer = new byte[2048];
                while (running) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    voiceSocket.receive(packet);
                    handleVoicePacket(packet);
                }
            } catch (Exception ex) {
                append("ERROR", "Voice UDP error: " + ex.getMessage());
            }
        }, "udp-voice");
        t.setDaemon(true);
        t.start();
    }

    private void handleVoicePacket(DatagramPacket packet) {
        byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
        int nl = -1;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                nl = i;
                break;
            }
        }
        if (nl <= 0) return;
        String nick = new String(data, 0, nl, StandardCharsets.UTF_8).trim();
        if (nick.isEmpty()) return;
        InetSocketAddress source = new InetSocketAddress(packet.getAddress(), packet.getPort());
        voiceAddresses.put(nick, source);

        int audioOffset = nl + 1;
        int audioLength = data.length - audioOffset;
        if (audioLength <= 0) return;

        if (speaker != null) {
            speaker.write(data, audioOffset, audioLength);
        }

        // Relay phone voice to other phones in the same PC session.
        for (Map.Entry<String, InetSocketAddress> entry : voiceAddresses.entrySet()) {
            if (entry.getKey().equals(nick)) continue;
            sendUdp(data, entry.getValue());
        }
    }

    private void initSpeaker() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        speaker = AudioSystem.getSourceDataLine(format);
        speaker.open(format, SAMPLE_RATE);
        speaker.start();
    }

    private void togglePcVoice() {
        pcVoice = !pcVoice;
        voiceButton.setText(pcVoice ? "Leave voice" : "Join voice");
        if (pcVoice) {
            append("SYSTEM", "PC voice enabled. Speak into your microphone.");
            startPcMicrophoneSender();
        } else {
            append("SYSTEM", "PC voice disabled.");
        }
    }

    private void startPcMicrophoneSender() {
        Thread t = new Thread(() -> {
            TargetDataLine mic = null;
            try {
                AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
                mic = AudioSystem.getTargetDataLine(format);
                mic.open(format, SAMPLE_RATE);
                mic.start();
                byte[] audio = new byte[VOICE_BUFFER];
                while (pcVoice && running) {
                    int read = mic.read(audio, 0, audio.length);
                    if (read > 0 && voiceSocket != null) {
                        byte[] payload = makeVoicePayload("PC", audio, read);
                        for (InetSocketAddress address : voiceAddresses.values()) {
                            sendUdp(payload, address);
                        }
                    }
                }
            } catch (Exception ex) {
                append("ERROR", "PC microphone error: " + ex.getMessage());
                pcVoice = false;
                SwingUtilities.invokeLater(() -> voiceButton.setText("Join voice"));
            } finally {
                if (mic != null) {
                    mic.stop();
                    mic.close();
                }
            }
        }, "pc-mic");
        t.setDaemon(true);
        t.start();
    }

    private byte[] makeVoicePayload(String name, byte[] audio, int len) {
        byte[] header = (name + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[header.length + len];
        System.arraycopy(header, 0, payload, 0, header.length);
        System.arraycopy(audio, 0, payload, header.length, len);
        return payload;
    }

    private void sendUdp(byte[] data, InetSocketAddress address) {
        try {
            DatagramPacket p = new DatagramPacket(data, data.length, address.getAddress(), address.getPort());
            voiceSocket.send(p);
        } catch (IOException ignored) {
        }
    }

    private void sendPcMessage() {
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        input.setText("");
        if (text.startsWith("/w ")) {
            String[] parts = text.split(" ", 3);
            if (parts.length < 3) {
                append("SYSTEM", "Usage: /w nick message");
                return;
            }
            ClientHandler target = clients.get(parts[1]);
            if (target == null) {
                append("SYSTEM", "User not found: " + parts[1]);
                return;
            }
            target.send("PM\tPC\t" + now() + "\t" + parts[2]);
            append("PM to " + parts[1], parts[2]);
        } else {
            String line = "MSG\tPC\t" + now() + "\t" + text;
            broadcast(line);
            append("PC", text);
        }
    }

    private void broadcast(String line) {
        for (ClientHandler c : clients.values()) c.send(line);
    }

    private void updateUsers() {
        SwingUtilities.invokeLater(() -> {
            usersModel.clear();
            List<String> names = new ArrayList<>(clients.keySet());
            Collections.sort(names);
            for (String name : names) usersModel.addElement(name);
        });
        String joined = String.join(",", clients.keySet());
        broadcast("USERS\t" + joined);
    }

    private void append(String from, String text) {
        SwingUtilities.invokeLater(() -> chatArea.append("[" + now() + "] " + from + ": " + text + "\n"));
    }

    private String now() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private String getLocalIp() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 80);
            return socket.getLocalAddress().getHostAddress();
        } catch (Exception ignored) {
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e) {
                return "127.0.0.1";
            }
        }
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private String nick;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                String hello = in.readLine();
                if (hello == null || !hello.startsWith("HELLO\t")) {
                    close();
                    return;
                }
                nick = hello.substring("HELLO\t".length()).trim();
                if (!nick.matches("[A-Za-z0-9_]{3,16}") || clients.containsKey(nick)) {
                    send("ERR\tBad nickname or nickname already used");
                    close();
                    return;
                }
                clients.put(nick, this);
                send("OK\tConnected to PC messenger");
                send("SYSTEM\t" + now() + "\tConnected. Voice UDP port: " + UDP_PORT);
                append("SYSTEM", nick + " connected");
                broadcast("SYSTEM\t" + now() + "\t" + nick + " joined chat");
                updateUsers();

                String line;
                while ((line = in.readLine()) != null) {
                    handleLine(line);
                }
            } catch (IOException ignored) {
            } finally {
                if (nick != null) {
                    clients.remove(nick);
                    voiceAddresses.remove(nick);
                    append("SYSTEM", nick + " disconnected");
                    broadcast("SYSTEM\t" + now() + "\t" + nick + " left chat");
                    updateUsers();
                }
                close();
            }
        }

        private void handleLine(String line) {
            if (line.startsWith("MSG\t")) {
                String text = line.substring(4).trim();
                if (!text.isEmpty()) {
                    append(nick, text);
                    broadcast("MSG\t" + nick + "\t" + now() + "\t" + text);
                }
            } else if (line.startsWith("PM\t")) {
                String[] parts = line.split("\t", 3);
                if (parts.length < 3) return;
                ClientHandler target = clients.get(parts[1]);
                if (target == null) {
                    send("SYSTEM\t" + now() + "\tUser not found: " + parts[1]);
                    return;
                }
                target.send("PM\t" + nick + "\t" + now() + "\t" + parts[2]);
                send("PM\tto " + parts[1] + "\t" + now() + "\t" + parts[2]);
            } else if (line.equals("VOICE_JOIN")) {
                append("VOICE", nick + " joined voice");
                broadcast("SYSTEM\t" + now() + "\t" + nick + " joined voice");
            } else if (line.equals("VOICE_LEAVE")) {
                voiceAddresses.remove(nick);
                append("VOICE", nick + " left voice");
                broadcast("SYSTEM\t" + now() + "\t" + nick + " left voice");
            }
        }

        void send(String line) {
            if (out != null) out.println(line);
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
