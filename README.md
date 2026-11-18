# IMS Books — Android app

Native Android front end for the Song Book Library. It uses the **same Spring Boot API** as the web app in
[`Song-book-Webapp`](../Song-book-Webapp), with no backend changes, and covers the member side of the web
frontend:

| Web route | Android screen |
|---|---|
| `/`, `/books` | **Books** tab: search, category chips, sort, infinite grid, pull to refresh |
| `/books/:id` | Book detail: Read, Download PDF (system "Save as"), "Log in to read", membership notice, Suggest a correction |
| `/login`, `/register`, `/forgot-password` | Mobile-number sign-in, registration (signed in at once), SMS-code password reset |
| `/me` | **My library** tab: membership, account, Continue reading, reading history, log out |
| `/me/profile` | Name/email, change mobile number (SMS code), change password (signs out everywhere) |
| `/me/requests` | My requests + new request (type, optional book search, description) |
| `/read/:id` | Reader: swipe/buttons/slider/go-to-page, pinch and double-tap zoom, progress saved and resumed |

The **admin console stays on the web**. Admins can still sign in on the phone and read every book.

Stack: Kotlin, Jetpack Compose (Material 3), Navigation (type-safe routes), Retrofit + OkHttp +
kotlinx.serialization, Coil 3 for covers, Android `PdfRenderer` for pages. minSdk 26 (Android 8), target/compile SDK 37.

## How it uses the API

- **Auth** follows the backend design (docs/SECURITY.md in the web repo). The 15-minute access token is held in
  memory only. The `sb_refresh` HttpOnly cookie is kept by `RefreshCookieJar` and persisted with
  AES-256-GCM using a non-exportable Android Keystore key, and it is excluded from backups and device transfer.
  A 401 triggers one *single-flight* refresh and a retry: the backend rotates refresh tokens and treats reuse
  as theft, so refreshes never overlap. A rejected refresh signs the user out.
  The backend accepts native clients as-is. Its refresh/logout Origin check only rejects a *wrong* `Origin`
  header, which apps never send, and SameSite does not apply outside browsers.
- **PDFs** use the ticket flow (`/read-access` → `/content?ticket=`). `PdfRenderer` needs a seekable file,
  so the current book is downloaded into the app's private cache. Only one book is kept; it is reused
  for the same version and deleted on log out. Eligibility is re-checked by the API every time a book is opened.
- **Covers** are fetched from `/api/v1/books/{id}/cover?v=…` with the app's HTTP client.

## Project layout

```
app/src/main/java/com/imsbooks/songbook/
  AppContainer.kt          manual DI: OkHttp, Retrofit, session, PDF repository
  data/                    API models + Retrofit interface, errors, PdfRepository
  data/session/            SessionManager (token, refresh, state), RefreshCookieJar, SecureStore (Keystore)
  ui/                      SongBookApp (navigation + tabs), theme, components
  ui/catalogue|book|auth|member|reader/   screens and their ViewModels
  util/                    mobile-number rules (same as backend/web), date formatting
app/src/test/              unit tests: mobile rules, cookie jar + refresh/retry against MockWebServer
infra/releases.bicep       Azure storage for APK downloads
.github/workflows/         CI + signed releases published to Azure
```

## Build flavours = environments

| Flavour | App id | API origin (Gradle property) |
|---|---|---|
| `local` | `com.imsbooks.songbook.local` | `songbook.apiBaseUrl.local` = `http://10.0.2.2:8080` (emulator → your PC) |
| `dev` | `com.imsbooks.songbook.dev` | `songbook.apiBaseUrl.dev` |
| `prod` | `com.imsbooks.songbook` | `songbook.apiBaseUrl.prod` |

Defaults are in `gradle.properties`. Override them per build with
`-Psongbook.apiBaseUrl.dev=https://…`, or in `~/.gradle/gradle.properties`. CI uses the `API_ORIGIN`
variable of each GitHub environment, the same one the web deploy uses. All three flavours can be
installed side by side. Plain HTTP is allowed **only** in debug builds and only to `10.0.2.2` / `localhost`.

## Run locally

Prerequisites: Android Studio (or JDK 17+ and the Android SDK), and the backend running locally:

```powershell
# in Song-book-Webapp/backend: embedded PostgreSQL, SMS codes printed to the console, dev admin 99999 99999
.\mvnw.cmd spring-boot:test-run
```

Then open this folder in Android Studio, pick the **localDebug** build variant and run on an emulator. From the
command line:

```powershell
.\gradlew.bat :app:installLocalDebug          # emulator/device must be connected
.\gradlew.bat :app:testLocalDebugUnitTest :app:lintDevRelease
```

On a physical phone, use your PC's LAN address instead, e.g.
`-Psongbook.apiBaseUrl.local=http://192.168.1.20:8080`, and add it to `app/src/debug/res/xml/network_security_config.xml`.

## Deploying with Azure

The API, database and PDF storage are already on Azure (web repo: `infra/main.bicep`, `deploy.yml`).
An Android app runs on the phone, so "mounting it to Azure" means three things:

1. **Point the builds at the Azure API.** Find the API URL in the web infra outputs (`apiOrigin`), or with
   `az webapp show -g <rg> -n <app> --query defaultHostName -o tsv`. Set it as `API_ORIGIN` in this repo's GitHub
   environments, and in `gradle.properties` for local release builds.
2. **Host the APKs on Azure** (`infra/releases.bicep`). This creates a small storage account per environment
   with a `releases` container: blobs are readable by link, the container cannot be listed, and uploads go
   through Entra ID only.
3. **CI/CD** (`.github/workflows/android.yml`). Every push runs the tests, lint and a debug build. A push to
   `dev` publishes a signed dev APK. A push to `main` also publishes prod, after approval, once `PROD_ENABLED` is
   `true`. This is the same model as the web repo.

Download links after a release:
`https://<account>.blob.core.windows.net/releases/<env>/songbook-<env>-latest.apk` (plus `latest.json` and versioned APKs).

### One-time setup

```bash
# 1. Storage for APKs (same resource group as the web environment). The principal id is the GitHub OIDC
#    service principal the web repo already uses: az ad sp show --id <AZURE_CLIENT_ID> --query id -o tsv
az deployment group create -g <rg-dev> -f infra/releases.bicep \
  -p environmentName=dev deployPrincipalId=<sp-object-id>
#    note the output storageAccountName

# 2. Let this repository log in with the existing app registration (one credential per environment)
az ad app federated-credential create --id <AZURE_CLIENT_ID> --parameters '{
  "name": "song-book-app-dev",
  "issuer": "https://token.actions.githubusercontent.com",
  "subject": "repo:<owner>/song-book-app:environment:dev",
  "audiences": ["api://AzureADTokenExchange"]
}'
```

3. Create a signing key (once; **back it up**, because losing it means users can't install updates):
   ```bash
   keytool -genkeypair -v -keystore upload.jks -alias songbook -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 upload.jks   # → secret ANDROID_KEYSTORE_BASE64
   ```
4. In GitHub, go to **Settings → Secrets and variables → Actions**:
   - Repository secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
   - Environment `dev` (and later `prod`, with required reviewers) variables: `API_ORIGIN`, `AZURE_CLIENT_ID`,
     `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `RELEASES_STORAGE_ACCOUNT`.
   - Repository variable `PROD_ENABLED=true` once prod is ready.

For local signed builds, put `storeFile`, `storePassword`, `keyAlias` and `keyPassword` in a git-ignored
`keystore.properties`, then run `.\gradlew.bat :app:assembleDevRelease`.

### Google Play (recommended for members)

Each release also produces an `.aab` (workflow artifact). Upload it to Google Play with the **prod** flavour and
Play App Signing. The key above then acts as the upload key. Azure-hosted APKs are best for testers and
side-loading, because Android asks users to allow "install unknown apps".

## Open decisions

- **Application id** `com.imsbooks.songbook` is a placeholder. It must be a domain you control before the first
  Play upload, and it can't be changed afterwards.
- **Refresh cookie on custom domains.** Unlike the browser, the app does not need the custom domains (D8) to keep
  sessions. The `*.azurewebsites.net` API hostname works for the app as-is.
