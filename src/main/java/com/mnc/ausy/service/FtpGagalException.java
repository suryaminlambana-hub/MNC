package com.mnc.ausy.service;

/**
 * Berkas ACCTXT berhasil disusun, tetapi GAGAL dikirim ke FTP.
 *
 * Sengaja berupa exception, bukan nilai kembalian: penandaan "sudah digenerate" pada data
 * penampungan harus ikut DIBATALKAN. Sebelumnya kegagalan FTP dikembalikan sebagai Result biasa,
 * sehingga transaksinya tetap commit — pesannya berbunyi "Export tetap menunggu; coba generate
 * lagi" padahal penampungannya sudah kosong, dan berkas yang tidak pernah sampai ke core tidak
 * bisa dikirim ulang oleh siapa pun.
 *
 * Isi berkasnya tetap dibawa (nama & jumlah baris) supaya riwayat bisa menyebut apa yang
 * sebenarnya sudah tersusun, bukan sekadar "gagal".
 */
public class FtpGagalException extends RuntimeException {

    private final String fileName;
    private final int rows;
    private final String tujuan;

    public FtpGagalException(String fileName, int rows, String tujuan, String sebab) {
        super(sebab);
        this.fileName = fileName;
        this.rows = rows;
        this.tujuan = tujuan;
    }

    public String getFileName() { return fileName; }
    public int getRows()        { return rows; }
    public String getTujuan()   { return tujuan; }

    /** Satu kalimat yang menyebut keduanya: apa yang berhasil, dan apa yang tidak. */
    public String pesan() {
        return "File " + fileName + " berhasil disusun (" + rows + " baris) tetapi GAGAL dikirim ke FTP "
                + tujuan + ": " + getMessage()
                + ". Data dikembalikan ke penampungan — perbaiki koneksi FTP lalu jalankan lagi.";
    }
}
