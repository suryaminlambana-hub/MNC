-- Penanda "Akun Utama" (V26) pindah dari akun seed ke akun milik MNC.
--
-- admin dan adminapv adalah akun bawaan instalasi. Di lingkungan MNC yang dipakai sehari-hari
-- adalah adminmnc dan adminapvmnc, jadi dua akun itulah yang tidak boleh hilang. Akun seed
-- kehilangan penandanya supaya bisa dihapus setelah tidak terpakai.
--
-- Migrasi ini hanya berjalan kalau kedua akun MNC benar-benar siap: adminmnc aktif di role
-- CONFIG / SETTING (id_group 1) dan adminapvmnc aktif di CONFIG APPROVER (id_group 2). Tanpa
-- keduanya, penanda lama dibiarkan. Aturan ini menjaga agar selalu ada akun yang bisa
-- mengonfigurasi dan akun yang bisa mengotorisasi konfigurasi. Pada database baru (mis. lokal
-- developer) akun MNC belum ada, jadi migrasi ini tidak mengubah apa pun.
DO $$
DECLARE cfg int; apv int;
BEGIN
  SELECT count(*) INTO cfg FROM users
   WHERE user_id = 'adminmnc'    AND id_group = 1 AND status_account = 1;
  SELECT count(*) INTO apv FROM users
   WHERE user_id = 'adminapvmnc' AND id_group = 2 AND status_account = 1;

  IF cfg = 1 AND apv = 1 THEN
    UPDATE users SET protected_account = 1 WHERE user_id IN ('adminmnc', 'adminapvmnc');
    UPDATE users SET protected_account = 0 WHERE user_id IN ('admin', 'adminapv');
    RAISE NOTICE 'Akun utama pindah ke adminmnc dan adminapvmnc.';
  ELSE
    RAISE NOTICE 'Akun utama tidak diubah: adminmnc=%, adminapvmnc=% (syarat: 1 dan 1).', cfg, apv;
  END IF;
END $$;
