package com.mnc.ausy.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pengiriman FTP berkas ACCTXT, diuji terhadap server FTP sungguhan lewat soket — bukan tiruan,
 * karena yang diuji justru perilaku di tingkat protokol: daftar isi folder dan penolakan STOR.
 *
 * Yang dijaga tes ini adalah kejadian nyata di mesin QA: folder export mesin itu ternyata folder
 * yang sama dengan root server FTP-nya, sehingga mengunggah berkas ke sana berarti menimpa berkas
 * yang sedang dibuka proses ini sendiri. Windows menolaknya dengan 550 "being used by another
 * process", dan satu hari penyusunan ACCTXT gagal seluruhnya.
 *
 * Servernya ditulis seadanya di sini (USER/PASS/PASV/NLST/STOR) supaya tidak menambah dependensi
 * hanya demi tes. Mesin tanpa python3 melewati tes ini alih-alih menggagalkannya.
 */
class FtpServiceProbeTest {

    private record Server(Process p, int port) {}

    /** Server FTP seadanya; lihat javadoc kelas. */
    private static final String FTPD = """
\"\"\"Server FTP minimal yang melayani SATU folder nyata di disk.
Cukup untuk menguji: NLST (penanda folder) dan STOR (unggahan).\"\"\"
import os, socket, sys, threading

ROOT = os.path.abspath(sys.argv[1])
PORT = int(sys.argv[2])
# Kalau di-set, STOR ditolak 550 seperti Windows yang berkasnya sedang dipegang proses lain.
LOCK_N = int(os.environ.get("LOCK_FIRST_N_STORES", "0"))
stores = [0]

def handle(conn):
    f = conn.makefile("rwb", buffering=0)
    def send(s): f.write((s + "\\r\\n").encode())
    send("220 uji siap")
    pasv_sock = None
    while True:
        line = f.readline()
        if not line: return
        cmd, _, arg = line.decode(errors="replace").strip().partition(" ")
        cmd = cmd.upper()
        if cmd in ("USER", "PASS"):
            send("230 masuk" if cmd == "PASS" else "331 sandi")
        elif cmd == "SYST":  send("215 UNIX Type: L8")
        elif cmd == "TYPE":  send("200 ok")
        elif cmd == "PWD":   send('257 "/"')
        elif cmd == "CWD":   send("250 ok")
        elif cmd == "PASV":
            pasv_sock = socket.socket(); pasv_sock.bind(("127.0.0.1", 0)); pasv_sock.listen(1)
            p = pasv_sock.getsockname()[1]
            send("227 Entering Passive Mode (127,0,0,1,%d,%d)" % (p >> 8, p & 0xFF))
        elif cmd in ("LIST", "NLST"):
            send("150 mulai"); d, _ = pasv_sock.accept()
            names = sorted(os.listdir(ROOT))
            body = "".join(n + "\\r\\n" for n in names) if cmd == "NLST" else \\
                   "".join("-rw-r--r-- 1 u g %d Jan 1 00:00 %s\\r\\n" % (os.path.getsize(os.path.join(ROOT, n)), n) for n in names)
            d.sendall(body.encode()); d.close(); send("226 selesai")
        elif cmd == "STOR":
            stores[0] += 1
            if stores[0] <= LOCK_N:
                send("550-The process cannot access the file because it is being used by another process.")
                send("550 End"); continue
            send("150 mulai"); d, _ = pasv_sock.accept()
            with open(os.path.join(ROOT, os.path.basename(arg)), "wb") as out:
                while True:
                    b = d.recv(65536)
                    if not b: break
                    out.write(b)
            d.close(); send("226 tersimpan")
        elif cmd == "QUIT": send("221 dadah"); return
        else: send("200 ok")

s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", PORT)); s.listen(5)
print("SIAP", PORT, ROOT, flush=True)
while True:
    c, _ = s.accept()
    threading.Thread(target=handle, args=(c,), daemon=True).start()
""";

    private static Path skrip;

    @BeforeAll
    static void siapkanSkrip() throws Exception {
        assumeTrue(adaPython3(), "python3 tidak ada — tes server FTP dilewati");
        skrip = Files.createTempFile("ausy-ftpd-", ".py");
        Files.writeString(skrip, FTPD);
    }

    @AfterAll
    static void bersihkan() throws Exception {
        if (skrip != null) Files.deleteIfExists(skrip);
    }

    private static boolean adaPython3() {
        try {
            return new ProcessBuilder("python3", "--version")
                    .redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    private Server start(Path root, int lockFirstN) throws Exception {
        int port = 15000 + (int) (Math.random() * 1000);
        ProcessBuilder pb = new ProcessBuilder("python3",
                skrip.toString(), root.toString(), String.valueOf(port));
        pb.environment().put("LOCK_FIRST_N_STORES", String.valueOf(lockFirstN));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String l = r.readLine();
        assertNotNull(l, "server FTP tidak start");
        assertTrue(l.startsWith("SIAP"), l);
        new Thread(() -> { try { while (r.readLine() != null) {} } catch (Exception ignore) {} }).start();
        return new Server(p, port);
    }

    private FtpService svc(int port) {
        FtpService s = new FtpService();
        for (Map.Entry<String, Object> e : Map.<String, Object>of(
                "host", "127.0.0.1", "port", port, "user", "u", "pass", "p",
                "dir", "", "passive", true).entrySet())
            ReflectionTestUtils.setField(s, e.getKey(), e.getValue());
        return s;
    }

    @Test
    void folderFtpSamaDenganFolderExport_unggahanDilewati() throws Exception {
        Path folder = Files.createTempDirectory("ausy-sama");
        Path berkas = folder.resolve("ACCTXT.261013");
        Files.writeString(berkas, "baris satu\n");
        // STOR SELALU ditolak: kalau unggahannya tetap dijalankan, uji ini gagal.
        Server s = start(folder, 99);
        try {
            String hasil = svc(s.port()).push(berkas);
            assertTrue(hasil.endsWith("/ACCTXT.261013"), hasil);
            assertEquals("baris satu\n", Files.readString(berkas), "berkas tidak boleh berubah");
            try (var isi = Files.list(folder)) {
                assertEquals(1, isi.count(), "penanda ausy-cek-* harus sudah dihapus");
            }
        } finally { s.p().destroy(); }
    }

    @Test
    void folderBerbeda_berkasBenarBenarTerunggah() throws Exception {
        Path lokal = Files.createTempDirectory("ausy-lokal");
        Path jauh  = Files.createTempDirectory("ausy-jauh");
        Path berkas = lokal.resolve("ACCTXT.261013");
        Files.writeString(berkas, "isi asli\n");
        Server s = start(jauh, 0);
        try {
            svc(s.port()).push(berkas);
            assertEquals("isi asli\n", Files.readString(jauh.resolve("ACCTXT.261013")));
            try (var isi = Files.list(lokal)) {
                assertEquals(1, isi.count(), "penanda di folder lokal harus sudah dihapus");
            }
        } finally { s.p().destroy(); }
    }

    @Test
    void berkasSedangDipegang_diulangLaluBerhasil() throws Exception {
        Path lokal = Files.createTempDirectory("ausy-lokal2");
        Path jauh  = Files.createTempDirectory("ausy-jauh2");
        Path berkas = lokal.resolve("ACCTXT.261013");
        Files.writeString(berkas, "isi\n");
        // Dua STOR pertama ditolak 550 "being used by another process"; percobaan ke-3 lolos.
        Server s = start(jauh, 2);
        try {
            svc(s.port()).push(berkas);
            assertEquals("isi\n", Files.readString(jauh.resolve("ACCTXT.261013")));
        } finally { s.p().destroy(); }
    }

    @Test
    void terkunciTerus_pesannyaMenyebutSebabnya() throws Exception {
        Path lokal = Files.createTempDirectory("ausy-lokal3");
        Path jauh  = Files.createTempDirectory("ausy-jauh3");
        Path berkas = lokal.resolve("ACCTXT.261013");
        Files.writeString(berkas, "isi\n");
        Server s = start(jauh, 99);
        try {
            Exception e = assertThrows(Exception.class, () -> svc(s.port()).push(berkas));
            assertTrue(e.getMessage().contains("dipegang proses lain"), e.getMessage());
            assertTrue(e.getMessage().contains("Sudah dicoba 3 kali"), e.getMessage());
        } finally { s.p().destroy(); }
    }
}
