-- Extra GL codes (termasuk Non-Active) supaya filter Status pada List GL ada isinya.
INSERT INTO gl_code (code,position,description,status,created_by) VALUES
 ('500','D','BIAYA SEWA GEDUNG',0,'system'),
 ('501','C','PENDAPATAN JASA (NONAKTIF)',0,'system'),
 ('502','D','BIAYA PENYUSUTAN AKTIVA',0,'system'),
 ('600','D','BIAYA PEMASARAN',1,'system'),
 ('601','C','TITIPAN SEMENTARA',1,'system');
