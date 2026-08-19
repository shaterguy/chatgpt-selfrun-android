#!/usr/bin/env python3
"""Derive the stable SelfRun TEST signing identity from the private SelfRun passphrase.

The TEST identity is domain-separated from the formal SelfRun signing identity so the two
installation lineages can never update or replace one another even when they share one secret.
"""

from __future__ import annotations

import argparse
import hashlib
import math
from datetime import datetime, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

from derive_signing_identity import BITS, E, probable_prime


def derive_prime(secret: bytes, label: bytes) -> int:
    raw = hashlib.shake_256(b"chatgpt-selfrun-test-signing-v1|" + secret + b"|" + label).digest(BITS // 8)
    candidate = int.from_bytes(raw, "big") | (1 << (BITS - 1)) | 1
    mask = (1 << BITS) - 1
    candidate &= mask
    candidate |= 1 << (BITS - 1)
    while True:
        if probable_prime(candidate):
            return candidate
        candidate += 2
        if candidate.bit_length() > BITS:
            candidate = (1 << (BITS - 1)) | 1


def derive_key(secret: bytes):
    p = derive_prime(secret, b"p")
    q = derive_prime(secret, b"q")
    if p == q:
        q = derive_prime(secret, b"q2")
    phi = (p - 1) * (q - 1)
    if math.gcd(E, phi) != 1:
        q = derive_prime(secret, b"q3")
        phi = (p - 1) * (q - 1)
    d = pow(E, -1, phi)
    numbers = rsa.RSAPrivateNumbers(
        p=p,
        q=q,
        d=d,
        dmp1=d % (p - 1),
        dmq1=d % (q - 1),
        iqmp=pow(q, -1, p),
        public_numbers=rsa.RSAPublicNumbers(E, p * q),
    )
    return numbers.private_key()


def derive_certificate(secret: bytes, key):
    subject = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "ChatGPT SelfRun Android Test"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "shaterguy"),
    ])
    serial = int.from_bytes(hashlib.sha256(b"chatgpt-selfrun-test-cert-v1|" + secret).digest()[:19], "big")
    serial = max(1, serial)
    return (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(serial)
        .not_valid_before(datetime(2026, 8, 19, tzinfo=timezone.utc))
        .not_valid_after(datetime(2056, 8, 19, tzinfo=timezone.utc))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pass-file", required=True)
    parser.add_argument("--out-p12")
    parser.add_argument("--out-key")
    parser.add_argument("--out-cert", required=True)
    args = parser.parse_args()
    if not args.out_p12 and not args.out_key:
        raise SystemExit("provide --out-p12 and/or --out-key")

    secret = Path(args.pass_file).read_bytes().strip()
    if len(secret) < 20:
        raise SystemExit("signing passphrase is unexpectedly short")
    key = derive_key(secret)
    cert = derive_certificate(secret, key)

    if args.out_p12:
        p12 = pkcs12.serialize_key_and_certificates(
            b"selfrun-test",
            key,
            cert,
            None,
            serialization.BestAvailableEncryption(secret),
        )
        Path(args.out_p12).write_bytes(p12)
    if args.out_key:
        Path(args.out_key).write_bytes(key.private_bytes(
            encoding=serialization.Encoding.DER,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        ))
    Path(args.out_cert).write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    print(cert.fingerprint(hashes.SHA256()).hex())


if __name__ == "__main__":
    main()
