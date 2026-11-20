# Bundled stock CSQC

`godnotic-stock-csqc.pk3` contains these programs under cache names derived
from the server's announced size and CRC:

- Xonotic 0.8.5: `dlcache/csprogs.dat.3910477.36081`, SHA-256
  `2fea8cded1a6515aaa96abd21078f60ba78cf709735c8abb606e9c507b634137`.
  Extracted by bounded HTTP range reads from the stored data pk3 in the
  official `https://dl.xonotic.org/xonotic-0.8.5.zip`; the ZIP entry CRC was
  verified during extraction.
- Xonotic 0.8.6 (2023-06-20): `dlcache/csprogs.dat.4021257.18349`.

The uncompressed program's SHA-256 is
`7d7807166d38521aadb8109830b69580596be4c05924d1b1fd8b71c31d4def13`.

The 0.8.6 bytes were checked against `csprogs.dat` extracted with bounded
HTTP range reads from the stored data pk3 in the official
`https://dl.xonotic.org/xonotic-0.8.6.zip`; its ZIP entry CRC was verified.
Servers that announce another size or CRC continue to download their own
program.
