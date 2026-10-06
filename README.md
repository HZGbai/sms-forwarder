# SMS Forwarder
[简体中文](README_zh.md) | English

A self-hosted SMS forwarding system. An Android client listens for incoming
messages on the device and pushes them to a Cloudflare Worker backend, which
stores them in KV and serves a lightweight web page for browsing.

*Important: Availability is only guaranteed on stock Android. If your system's restrictions prevent the app from being woken up, messages cannot be received.*

## Overview

- **Client** — event driven. A message is uploaded as soon as it arrives, with a
  persistent on-disk queue and retry on failure, so nothing is lost while
  offline.
- **Server** — a single Cloudflare Worker backed by a KV namespace.
- **Access control** — two independent layers. A WAF whitelist field rejects
  unauthorised requests at the edge before they ever reach the Worker (which
  keeps them off your Workers quota), and the Worker itself checks the `ADMIN`
  password.
- **Web UI** — served by the Worker itself. Enter the password to browse the
  stored messages, with optional auto-refresh.

### Layout

- `client/` — Android app source
- `server/` — Cloudflare Worker source and configuration

## Deploying the backend

The easiest route is to deploy from a GitHub repository through the Cloudflare
dashboard.

1. **Fork this repository** to your own GitHub account.
2. In the Cloudflare dashboard, go to **Workers & Pages** → **Create** →
   **Connect to Git**.
3. Select the repository you just forked. **Set the root directory to
   `/server`** — this is required.
4. Set the deploy command to `npx wrangler deploy --keep-vars`.
5. Configure the KV namespace and environment variables (see below) before
   deploying.
6. Deploy.

### KV and environment variables

Open the Worker's settings and bind resources there. Do **not** commit your
password into `wrangler.toml`.

- **KV binding** — create a KV namespace and put its id into `wrangler.toml`:

  ```
  wrangler kv namespace create kv
  ```

  The binding name must be `kv`; the Worker looks it up by that name.

- **Environment variables** — add these under **Settings** → **Variables**:

  | Name          | Required | Description                                  |
  |---------------|----------|----------------------------------------------|
  | `ADMIN`       | yes      | Admin password. Store it as a *secret*.      |
  | `MAX_RECORDS` | no       | Maximum number of stored messages (200).     |

  From the CLI, `ADMIN` is set with:

  ```
  wrangler secret put ADMIN
  ```

### WAF rules

Both rules are configured in the Cloudflare dashboard (**Security** → **WAF** →
**Custom rules**), not in the code.

1. **Skip rule** — exempt the API paths from bot protection, because the Android
   client cannot execute the JavaScript that a managed challenge requires:

   - Expression: `http.request.full_uri wildcard r"https://<your-domain>/api/*"`
   - Action: *Skip* → skip all remaining custom rules and managed rules

2. **Block rule** — reject any request that does not carry the whitelist field:

   - Expression: `not http.request.full_uri contains "<your-access-field>"`
   - Action: *Block*

The **block rule must be ordered below the skip rule**, otherwise the skip rule
never takes effect.

> The field is matched against the query string, so the client sends it as a URL
> parameter. It also sends it as a request header, which does nothing today but
> means the rule can be tightened to headers later without touching the app.

### Verifying the deployment

```
curl "https://<your-domain>/api/health"
```

A correctly configured Worker answers with JSON. If you get an error instead,
see [Troubleshooting](#troubleshooting).

## Building the Android client

### Requirements

- **JDK 17 or newer** (the Android Studio bundled JBR works)
- **Android SDK platform 37** with build-tools 36.0.0
- **AGP 9.x** and Gradle 9.x — AGP 9 already bundles Kotlin support, so the
  `org.jetbrains.kotlin.android` plugin must *not* be applied

### From the command line

Create `client/local.properties` with the path to your SDK, using **forward
slashes**. Escaped backslashes make AGP 9 throw `Invalid file path`:

```
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

Then:

```
cd client
./gradlew assembleDebug      # debug build, allows cleartext HTTP
./gradlew assembleRelease    # release build, cleartext HTTP blocked
```

Outputs land in `client/app/build/outputs/apk/`.

If the project path contains non-ASCII characters, AGP refuses to build unless
`android.overridePathCheck=true` is set in `client/gradle.properties`. It is
already set in this repository.

### Signing release builds

Signing is optional. Put the credentials in `client/app/keystore.properties`;
`storeFile` is resolved relative to the `app` module, and an absolute path also
works.

```
storeFile=my-release.p12
storePassword=...
keyAlias=...
keyPassword=...
storeType=PKCS12
```

To create a keystore:

```
keytool -genkeypair -v -keystore my-release.p12 -storetype PKCS12 \
  -alias mykey -keyalg RSA -keysize 2048 -validity 10000
```

If `keystore.properties` is absent, the release build silently produces an
unsigned APK instead of failing.

## Using the Android client

1. Install the APK on your phone.
2. Open the app and fill in:

   - **Server URL** — your domain, e.g. `https://sms.example.com`
   - **Access field** — the WAF whitelist field from the block rule above; the
     `access_` prefix is added automatically if you omit it
   - **ADMIN password** — must match the `ADMIN` secret on the server

3. Grant the SMS permissions when prompted.
4. Turn on the forwarding switch.

The app also has buttons to test connectivity, upload the existing inbox as a
one-off backfill, flush the queue manually, and inspect the on-device log — all
of which are useful when something is not arriving.

## Use carefully, read this first: notes and caveats

- **Never commit your password.** Keep `ADMIN` in a Worker secret, and add
  `keystore.properties` to `.gitignore`.
- **Both layers must agree on the access field.** The value is required in three
  places: the WAF block rule, the app's *Access field* setting, and the web
  page's `access_...` box. Change one and the other two stop working.
- **Keep `workers_dev = false`.** WAF rules only apply to your custom domain, so
  leaving the `*.workers.dev` route enabled would let anyone bypass every rule.
- **Do not put new endpoints outside `/api/`.** The skip rule only covers
  `/api/*`. A path outside it hits the managed challenge, which needs
  JavaScript, and the Android client cannot pass it.
- **The web page can only be reached from a browser that already passed the
  challenge.** The skip rule deliberately covers `/api/*` only.
- **Losing the signing key means you can no longer ship upgrades.** Android
  refuses to install a build signed with a different key over an existing one
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and uninstalling wipes all settings and
  any queued messages. Back the keystore up somewhere offline.
- **Debug and release builds differ.** Release blocks cleartext HTTP, so a plain
  `http://` LAN address will fail there; use the debug build for local testing.
  Release also sets `debuggable=false`, so `run-as` cannot read the on-device
  log.
- **Storage is a ring buffer.** Once `MAX_RECORDS` is exceeded, the oldest
  records are dropped.
- **This is a personal-use project.** There is no multi-user support and no rate
  limiting. Treat the web UI as a private page.

## Troubleshooting

`403` is returned by more than one layer, and the status code alone does not say
which. Check the response header first, then the body:

| Source                    | Signature                               | Fix                                   |
|---------------------------|-----------------------------------------|---------------------------------------|
| Cloudflare managed challenge | `Cf-Mitigated: challenge`            | Add the skip rule for `/api/*`        |
| WAF block rule            | HTML mentioning it has been blocked     | Check the access field                |
| Worker admin check        | JSON body `{"ok":false,...}`            | Check the `ADMIN` password            |

The first case is the one most easily misread: it has nothing to do with the
access field.

| Symptom                                    | Likely cause                                  |
|--------------------------------------------|-----------------------------------------------|
| `Missing KV binding`                       | KV namespace not bound, or not named `kv`      |
| `Server misconfigured: ADMIN is not set`   | The `ADMIN` secret is missing                  |
| Messages stay queued and never upload      | Wrong server URL, password, or access field    |
| Web page loads but the list stays empty    | Block rule matched, or no messages stored yet  |

**Note:** When a large batch of messages arrives at once, the web UI may lag behind. This delay is usually under 30 seconds.

## License

MIT License.
