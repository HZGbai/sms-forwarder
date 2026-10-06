# 短信转发器

简体中文 | [English](README.md)

一个自托管的短信转发系统。安卓客户端监听手机收到的短信，将其推送到 Cloudflare
Worker 后端，后端把短信存进 KV，并提供一个轻量网页用于浏览。

*重要：仅保证在原生安卓上可用。如果你的系统限制导致应用无法被唤醒，则无法接收短信。*

## 在你开始使用或部署本项目之前

如果你想尝试本项目，请先回答下列问题。如果你不知道或完全不理解这些问题，则相当于回答“否”：

1. 你是否使用**原生安卓**？
2. 你是否会部署 Workers？
3. 你是否能访问 Workers？

如果你有任意一个问题的回答为“否”，那么则不建议你尝试。

如果你不懂，且仍然坚决要尝试，请不要提出任何非程序漏洞的 issue。

如果你对上述任意的文字无法完全理解，那么建议你马上离开。

## 系统组成

- **客户端** —— 事件驱动。短信一到就立即上传，本地有持久化的磁盘队列并在失败时重试，
  因此离线期间的消息不会丢失。
- **服务端** —— 一个 Cloudflare Worker，背后挂一个 KV 命名空间。
- **访问控制** —— 两层彼此独立的防线。WAF 白名单字段在边缘就拒掉未授权的请求，
  请求根本到不了 Worker（这也就不会消耗你的 Workers 配额）；Worker 自身再校验
  `ADMIN` 密码。
- **网页界面** —— 由 Worker 自己提供。输入密码即可浏览已存储的短信，支持可选的自动刷新。

### 目录结构

- `client/` —— 安卓应用源码
- `server/` —— Cloudflare Worker 源码与配置

## 部署后端

最省事的做法是通过 Cloudflare 控制台，从 GitHub 仓库部署。

1. **Fork 本仓库**到你自己的 GitHub 账号。
2. 进入 Cloudflare 控制台的 **Workers & Pages** → **Create** → **Connect to Git**。
3. 选择你刚 Fork 的仓库。**把根目录设为 `/server`** —— 这一步是必须的。
4. 把部署命令设为 `npx wrangler deploy --keep-vars`。
5. 部署前先配置好 KV 命名空间和环境变量（见下文）。
6. 点击部署。

### KV 与环境变量

在 Worker 的设置页面里绑定资源。**不要**把密码写进 `wrangler.toml` 并提交。

- **KV 绑定** —— 创建 KV 命名空间，并把它的 id 填进 `wrangler.toml`：

  ```
  wrangler kv namespace create kv
  ```

  绑定名必须是 `kv`，Worker 是按这个名字去取的。

- **环境变量** —— 在 **Settings** → **Variables** 里添加：

  | 名称          | 是否必需 | 说明                              |
  |---------------|----------|-----------------------------------|
  | `ADMIN`       | 必需     | 管理员密码。请存为 *secret*。     |
  | `MAX_RECORDS` | 可选     | 最大存储条数（默认 200）。        |

  用命令行设置 `ADMIN`：

  ```
  wrangler secret put ADMIN
  ```

### WAF 规则

两条规则都在 Cloudflare 控制台里配置（**Security** → **WAF** → **Custom rules**），
不在代码里。

1. **跳过规则** —— 让 API 路径免于人机校验，因为安卓客户端无法执行托管挑战所需的
   JavaScript：

   - 表达式：`http.request.full_uri wildcard r"https://<你的域名>/api/*"`
   - 操作：*Skip* → skip all remaining custom rules and managed rules

2. **拦截规则** —— 拒绝任何未携带白名单字段的请求：

   - 表达式：`not http.request.full_uri contains "<你的访问字段>"`
   - 操作：*Block*

**拦截规则必须排在跳过规则下面**，否则跳过规则永远不会生效。

> 该字段是按查询串匹配的，所以客户端把它作为 URL 参数发送。客户端同时也会把它放在
> 请求头里，这在目前不起作用，但意味着以后可以在不改动应用的前提下把规则收紧到请求头。

### 验证部署

```
curl "https://<你的域名>/api/health"
```

配置正确的 Worker 会返回 JSON。如果返回的是错误，请见[疑难排查](#疑难排查)。

## 构建安卓客户端

### 环境要求

- **JDK 17 或更新版本**（Android Studio 自带的 JBR 即可）
- **Android SDK platform 37** 与 build-tools 36.0.0
- **AGP 9.x** 与 Gradle 9.x —— AGP 9 已内置 Kotlin 支持，因此**不要**再应用
  `org.jetbrains.kotlin.android` 插件

### 命令行构建

创建 `client/local.properties`，写入你的 SDK 路径，**必须用正斜杠**。
用转义反斜杠会让 AGP 9 抛出 `Invalid file path`：

```
sdk.dir=C:/Users/<你的用户名>/AppData/Local/Android/Sdk
```

然后：

```
cd client
./gradlew assembleDebug      # debug 包，允许明文 HTTP
./gradlew assembleRelease    # release 包，禁止明文 HTTP
```

产物位于 `client/app/build/outputs/apk/`。

如果工程路径含有非 ASCII 字符，AGP 会拒绝构建，除非在
`client/gradle.properties` 里设置 `android.overridePathCheck=true`。
本仓库已经设置好了。

### 为 release 包签名

签名是可选的。把凭据放进 `client/app/keystore.properties`；
`storeFile` 相对于 `app` 模块解析，写绝对路径也可以。

```
storeFile=my-release.p12
storePassword=...
keyAlias=...
keyPassword=...
storeType=PKCS12
```

生成密钥库：

```
keytool -genkeypair -v -keystore my-release.p12 -storetype PKCS12 \
  -alias mykey -keyalg RSA -keysize 2048 -validity 10000
```

如果 `keystore.properties` 不存在，release 构建会静默产出未签名的 APK，而不是报错。

## 使用安卓客户端

1. 在手机上安装 APK。
2. 打开应用，填写：

   - **服务器地址** —— 你的域名，例如 `https://sms.example.com`
   - **访问字段** —— 上面拦截规则里用的 WAF 白名单字段；如果省略 `access_`
     前缀，会自动补上
   - **ADMIN 密码** —— 必须与服务端的 `ADMIN` secret 一致

3. 按提示授予短信权限。
4. 打开转发开关。

应用里还有一些按钮用于测试连通性、一次性回传现有收件箱、手动清空队列、
查看设备端日志 —— 当消息收不到时，这些都用得上。

## 请谨慎使用，先读这一节：注意事项

- **永远不要把密码提交进仓库。** 把 `ADMIN` 存为 Worker secret，并把
  `keystore.properties` 加进 `.gitignore`。
- **两层防线的访问字段必须一致。** 这个值出现在三个地方：WAF 拦截规则、应用的
  *访问字段* 设置、网页上的 `access_...` 输入框。改掉其中一个，另外两个就会失效。
- **保持 `workers_dev = false`。** WAF 规则只作用于你的自定义域名，
  留着 `*.workers.dev` 路由等于让任何人都能绕过全部规则。
- **不要把新接口放在 `/api/` 之外。** 跳过规则只覆盖 `/api/*`。
  放在外面的路径会撞上托管挑战，而它需要执行 JavaScript，安卓客户端过不去。
- **网页只能从已经通过挑战的浏览器访问。** 跳过规则是刻意只覆盖 `/api/*` 的。
- **丢失签名密钥就再也发不出升级包了。** 安卓拒绝用不同密钥签名的包覆盖安装
  （`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），而卸载会清空所有设置和待发队列。
  请把密钥库离线备份好。
- **debug 包和 release 包行为不同。** release 禁止明文 HTTP，所以纯 `http://`
  的局域网地址在 release 上会失败；本地测试请用 debug 包。
  release 还设置了 `debuggable=false`，因此 `run-as` 读不到设备端日志。
- **存储是环状的。** 一旦超出 `MAX_RECORDS`，最早的记录会被丢弃。
- **这是个人自用项目。** 没有多用户支持，也没有限流。请把网页当作私人页面看待。

## 疑难排查

`403` 可能来自不止一层，仅凭状态码无法判断是哪一层。先看响应头，再看响应体：

| 来源                     | 特征                                    | 解决办法                        |
|--------------------------|-----------------------------------------|---------------------------------|
| Cloudflare 托管挑战      | `Cf-Mitigated: challenge`               | 为 `/api/*` 添加跳过规则        |
| WAF 拦截规则             | HTML 里提到请求已被阻止                 | 检查访问字段                    |
| Worker 自身的管理员校验  | JSON 响应体 `{"ok":false,...}`          | 检查 `ADMIN` 密码               |

第一种最容易被误判：它和访问字段没有任何关系。

| 现象                                    | 可能原因                                  |
|-----------------------------------------|-------------------------------------------|
| `Missing KV binding`                    | 没绑定 KV 命名空间，或名字不是 `kv`        |
| `Server misconfigured: ADMIN is not set`| 缺少 `ADMIN` secret                       |
| 消息一直积压在队列里传不上去            | 服务器地址、密码或访问字段填错了           |
| 网页能打开但列表一直是空的              | 被拦截规则命中，或确实还没有存过短信       |

**注意：** 当大量短信一次性到达时，网页界面可能会有延迟。该延迟通常不超过 30 秒。

## 协议

MIT License。
