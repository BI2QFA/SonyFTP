package bi2qfa.sony.ftp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 自包含的匿名只读 FTP 服务器（被动模式）。
 *
 * - 只实现浏览/下载命令，所有写命令回 550。
 * - 根目录限定在传入的 rootDir 内，防止 ../ 逃逸。
 * - 仅支持被动模式（PASV / EPSV）。
 */
public class FtpServer {

    public interface Listener {
        void onLog(String msg);
    }

    private final File rootDir;
    private final int port;
    private final Listener listener;

    private ServerSocket controlSocket;
    private Thread acceptThread;
    private volatile boolean running = false;

    public FtpServer(File rootDir, int port, Listener listener) {
        this.rootDir = rootDir;
        this.port = port;
        this.listener = listener;
    }

    public synchronized void start() throws IOException {
        controlSocket = new ServerSocket(port);
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Socket client = controlSocket.accept();
                        new Session(client).start();
                    } catch (IOException e) {
                        if (running) log("连接错误: " + e.getMessage());
                    }
                }
            }
        }, "FtpAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public int getPort() {
        return controlSocket == null ? port : controlSocket.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (controlSocket != null) controlSocket.close();
        } catch (IOException e) {}
        controlSocket = null;
    }

    private void log(String msg) {
        if (listener != null) listener.onLog(msg);
    }

    private void reply(BufferedWriter out, String s) throws IOException {
        out.write(s + "\r\n");
        out.flush();
    }

    private void closeQuietly(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (IOException e) {}
    }

    // Socket / ServerSocket 在 Java 6 未实现 Closeable，需单独重载
    private void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (IOException e) {}
    }

    private void closeQuietly(ServerSocket s) {
        try { if (s != null) s.close(); } catch (IOException e) {}
    }

    // ============ 会话 ============

    private class Session extends Thread {
        private final Socket control;
        private BufferedReader in;
        private BufferedWriter out;
        private File cwd;
        private ServerSocket pasvSocket;
        // 预建格式化器，避免每次列目录/取时间都 new（相机 CPU 弱，省开销）
        private final SimpleDateFormat listDateFormat = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
        private final SimpleDateFormat mdtmDateFormat = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);

        Session(Socket control) {
            this.control = control;
            this.cwd = rootDir;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(control.getInputStream(), "UTF-8"));
                out = new BufferedWriter(new OutputStreamWriter(control.getOutputStream(), "UTF-8"));
                control.setTcpNoDelay(true);   // 命令通道关 Nagle，响应更快
                reply(out, "220 Welcome to SonyFTP (read-only)");
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.length() == 0) continue;
                    handle(line);
                }
            } catch (IOException e) {
                // 连接断开
            } finally {
                closeQuietly(pasvSocket);
                closeQuietly(in);
                closeQuietly(out);
                closeQuietly(control);
            }
        }

        private void handle(String line) {
            try {
                String cmd;
                String arg = "";
                int sp = line.indexOf(' ');
                if (sp < 0) {
                    cmd = line;
                } else {
                    cmd = line.substring(0, sp);
                    arg = line.substring(sp + 1);
                }
                dispatch(cmd.trim().toUpperCase(Locale.US), arg.trim());
            } catch (IOException e) {
                closeQuietly(control);
            } catch (Throwable t) {
                try { reply(out, "550 " + t.getMessage()); } catch (IOException e) {}
            }
        }

        private void dispatch(String cmd, String arg) throws IOException {
            if (cmd.equals("USER")) { reply(out, "331 Please specify the password."); }
            else if (cmd.equals("PASS")) { reply(out, "230 Login successful (anonymous)."); }
            else if (cmd.equals("SYST")) { reply(out, "215 UNIX Type: L8"); }
            else if (cmd.equals("FEAT")) {
                reply(out, "211-Features:");
                reply(out, " UTF8");
                reply(out, "211 End");
            }
            else if (cmd.equals("TYPE")) { reply(out, "200 Type set to I."); }
            else if (cmd.equals("OPTS")) { reply(out, "200 OK."); }
            else if (cmd.equals("NOOP")) { reply(out, "200 NOOP ok."); }
            else if (cmd.equals("HELP")) { reply(out, "214 Commands: USER PASS SYST FEAT TYPE PWD CWD CDUP PASV EPSV LIST NLST RETR SIZE MDTM QUIT NOOP."); }
            else if (cmd.equals("PWD")) {
                reply(out, "257 \"" + virtualPath(cwd) + "\" is current directory.");
            }
            else if (cmd.equals("CWD")) { doCwd(arg); }
            else if (cmd.equals("CDUP")) { doCwd(".."); }
            else if (cmd.equals("PASV")) { doPasv(); }
            else if (cmd.equals("EPSV")) { doEpsv(arg); }
            else if (cmd.equals("LIST")) { doList(arg); }
            else if (cmd.equals("NLST")) { doNlst(arg); }
            else if (cmd.equals("RETR")) { doRetr(arg); }
            else if (cmd.equals("SIZE")) { doSize(arg); }
            else if (cmd.equals("MDTM")) { doMdtm(arg); }
            else if (cmd.equals("QUIT")) { reply(out, "221 Goodbye."); closeQuietly(control); }
            // 写命令全部拒绝
            else if (cmd.equals("STOR") || cmd.equals("STOU") || cmd.equals("APPE")
                    || cmd.equals("DELE") || cmd.equals("RNFR") || cmd.equals("RNTO")
                    || cmd.equals("MKD") || cmd.equals("RMD") || cmd.equals("SITE")
                    || cmd.equals("ALLO") || cmd.equals("CHMOD")) {
                reply(out, "550 Permission denied (read-only server).");
            }
            else { reply(out, "500 Unknown command."); }
        }

        // ===== 目录操作 =====

        private void doCwd(String arg) throws IOException {
            File target = resolveVirtual(arg);
            if (!target.isDirectory()) {
                reply(out, "550 Not a directory.");
                return;
            }
            cwd = target;
            reply(out, "250 Directory changed to \"" + virtualPath(cwd) + "\".");
        }

        private String virtualPath(File f) throws IOException {
            String root = rootDir.getCanonicalPath();
            String canon = f.getCanonicalPath();
            if (canon.equals(root)) return "/";
            String rel = canon.substring(root.length() + 1);
            return "/" + rel;
        }

        // 把虚拟路径（FTP 视角）解析成根目录内的真实文件，禁止逃逸
        private File resolveVirtual(String arg) throws IOException {
            if (arg == null || arg.length() == 0) return rootDir;
            File base = arg.startsWith("/") ? rootDir : cwd;
            File target = new File(base, arg);
            String canon = target.getCanonicalPath();
            String rootCanon = rootDir.getCanonicalPath();
            if (!canon.equals(rootCanon) && !canon.startsWith(rootCanon + File.separator)) {
                throw new SecurityException("Access denied.");
            }
            return target;
        }

        // ===== 被动模式 =====

        private void doPasv() throws IOException {
            closeQuietly(pasvSocket);
            pasvSocket = new ServerSocket(0, 1, InetAddress.getByName("0.0.0.0"));
            int p = pasvSocket.getLocalPort();
            String ip = control.getLocalAddress().getHostAddress().replace('.', ',');
            reply(out, "227 Entering Passive Mode (" + ip + "," + (p / 256) + "," + (p % 256) + ").");
        }

        private void doEpsv(String arg) throws IOException {
            closeQuietly(pasvSocket);
            pasvSocket = new ServerSocket(0, 1, InetAddress.getByName("0.0.0.0"));
            int p = pasvSocket.getLocalPort();
            reply(out, "229 Entering Extended Passive Mode (|||" + p + "|).");
        }

        private Socket acceptData() throws IOException {
            if (pasvSocket == null) {
                reply(out, "425 Use PASV or EPSV first.");
                return null;
            }
            ServerSocket ss = pasvSocket;
            pasvSocket = null;
            Socket data = ss.accept();
            closeQuietly(ss);
            // 数据通道：关 Nagle + 放大收发缓冲，提升吞吐
            data.setTcpNoDelay(true);
            data.setSendBufferSize(256 * 1024);
            data.setReceiveBufferSize(256 * 1024);
            return data;
        }

        // ===== 列出 / 下载 =====

        private void doList(String arg) throws IOException {
            reply(out, "150 Opening data connection for directory listing.");
            Socket data = null;
            try {
                data = acceptData();
                if (data == null) return;
                BufferedWriter dw = new BufferedWriter(new OutputStreamWriter(data.getOutputStream(), "UTF-8"));
                File[] files = cwd.listFiles();
                if (files != null) {
                    for (File f : files) {
                        dw.write(listLine(f));
                        dw.write("\r\n");
                    }
                }
                dw.flush();
                reply(out, "226 Directory send OK.");
            } catch (IOException e) {
                reply(out, "425 Error listing directory.");
            } finally {
                closeQuietly(data);
            }
        }

        private void doNlst(String arg) throws IOException {
            reply(out, "150 Opening data connection for file list.");
            Socket data = null;
            try {
                data = acceptData();
                if (data == null) return;
                BufferedWriter dw = new BufferedWriter(new OutputStreamWriter(data.getOutputStream(), "UTF-8"));
                File[] files = cwd.listFiles();
                if (files != null) {
                    for (File f : files) {
                        dw.write(f.getName());
                        dw.write("\r\n");
                    }
                }
                dw.flush();
                reply(out, "226 File list send OK.");
            } catch (IOException e) {
                reply(out, "425 Error listing.");
            } finally {
                closeQuietly(data);
            }
        }

        private void doRetr(String arg) throws IOException {
            File f = resolveVirtual(arg);
            if (!f.exists() || f.isDirectory()) {
                reply(out, "550 No such file or directory.");
                return;
            }
            reply(out, "150 Opening binary mode data connection for " + f.getName() + " (" + f.length() + " bytes).");
            Socket data = null;
            FileInputStream fin = null;
            try {
                data = acceptData();
                if (data == null) return;
                OutputStream dos = data.getOutputStream();
                fin = new FileInputStream(f);
                // 128KB 大缓冲：减少 read/write 系统调用次数（相机 CPU 弱，越少越快）
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = fin.read(buf)) > 0) {
                    dos.write(buf, 0, n);
                }
                dos.flush();
                reply(out, "226 Transfer complete.");
            } catch (IOException e) {
                reply(out, "426 Transfer aborted.");
            } finally {
                closeQuietly(fin);
                closeQuietly(data);
            }
        }

        private void doSize(String arg) throws IOException {
            File f = resolveVirtual(arg);
            if (!f.exists() || f.isDirectory()) {
                reply(out, "550 No such file.");
                return;
            }
            reply(out, "213 " + f.length());
        }

        private void doMdtm(String arg) throws IOException {
            File f = resolveVirtual(arg);
            if (!f.exists()) {
                reply(out, "550 No such file.");
                return;
            }
            String t = mdtmDateFormat.format(new Date(f.lastModified()));
            reply(out, "213 " + t);
        }

        private String listLine(File f) {
            String perms = f.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--";
            String date = listDateFormat.format(new Date(f.lastModified()));
            long size = f.isDirectory() ? 4096 : f.length();
            return perms + " 1 owner group " + size + " " + date + " " + f.getName();
        }
    }
}
