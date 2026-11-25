# Security & Privacy Policy

## 100% On-Device Privacy Architecture
**Jumpvex** is engineered to be **zero-cloud by design**:
- Camera frames, pose landmarks, microphone audio, and exported videos **never leave your Android device**.
- Workout history and diagnostic CSV logs are stored strictly on local storage.
- The `INTERNET` permission is used **exclusively** for optional, user-initiated GitHub Releases update checks (`api.github.com/repos/iganramdani/Jumpvex/releases/latest`).

## Verifying Official APKs
Every official release published by GitHub Actions includes a `SHA256SUMS.txt` manifest. Verify your downloaded APK before installing:

```bash
sha256sum -c SHA256SUMS.txt
```

## Reporting a Vulnerability
If you discover a security or privacy vulnerability in Jumpvex, please report it responsibly via [GitHub Security Advisories](https://github.com/iganramdani/Jumpvex/security/advisories/new) or open a minimal issue requesting private contact with `@iganramdani`. Please do not publicly disclose exploitable details until a fix is released.
