# Public certificate fixture

`isrg-root-x1.pem` is the public ISRG Root X1 certificate, exported read-only
from the bundled Microsoft JDK 21.0.12 trust store with `keytool -exportcert`.
Its DER SHA-256 is
`96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6`.
OpenSSL independently reports serial `8210CFB0D240E3594463E0BB63828B00`,
validity from `2015-06-04 11:04:38 UTC` to `2035-06-04 11:04:38 UTC`.

It is used only for JVM parsing/display tests. No private key is included,
and the fixture is not installed into Android or Gecko's trust stores.
