# AutoX 发布经验记录（GitHub Release 上传 APK）

> 记录时间：2026-09-10 ｜ 场景：suncV7 v1.0.0 发布到 
>
> [github.com/sunc-Q/AutoX](https://github.com/sunc-Q/AutoX)

## 一、完整发布流程（已验证可行）



```
\# 1. 构建已签名发布版（签名配置在 \~/.gradle/gradle.properties，keystore 在 \~/keystores/）

cd /Users/apple/DoubaoWork/chats/2026-09-10/new-chat-3/AutoX

export JAVA\_HOME=/Users/apple/.workbuddy/binaries/java/jdk-17.0.20/Contents/Home

export HTTPS\_PROXY=http://127.0.0.1:7897 HTTP\_PROXY=http://127.0.0.1:7897

./gradlew :app:assembleSuncV7Release

\# 2. 打 tag 并推送

git tag -a v1.0.1 -m "suncV7 v1.0.1"

git push origin v1.0.1

\# 3. 创建 Release + 上传 APK（gh CLI 已装好并认证）

cd /Users/apple/DoubaoWork/chats/2026-09-10/new-chat-3/AutoX

gh release create v1.0.1 -R sunc-Q/AutoX \\

&#x20; app/build/outputs/apk/suncV7/release/app-suncV7-arm64-v8a-release.apk \\

&#x20; \--title "v1.0.1" --notes "更新说明"

\# 4. 验证

gh release view v1.0.1 -R sunc-Q/AutoX

curl -sIL -x http://127.0.0.1:7897 -o /dev/null -w "%{http\_code}\n" \\

&#x20; https://github.com/sunc-Q/AutoX/releases/download/v1.0.1/app-suncV7-arm64-v8a-release.apk
```

## 二、本次踩过的坑（务必记住）

### 坑 1：GitHub Release 资产上传是【单请求】，不是分块上传



* 现象：`gh release upload` 卡住 / 超时；自写分块脚本第 2 块报 **HTTP 422**

* 原因：官方 REST API 只支持一次 POST 传整个文件（`--data-binary @file`）。分块上传（Content-Range `bytes 0-N/*`）会被当成 "完整文件" 注册成残缺资产，后续同名块全部 422

* 结论：**别写分块逻辑**，直接单请求。中途断了只能重传

### 坑 2：本机代理上行带宽极小，上传极慢



* 实测：下载 **1.29 MB/s** vs 上传 **0.1 MB/s**（差 12 倍）——Clash 节点上行受限

* 143M APK 实测约 **45 分钟**传完（稳定 100-115KB/s）

* 对策：换上行大的节点可显著加速；上传务必用 `-x http://127.0.0.1:7897` 且超时设大（`-m 7200`）；后台运行 + 进度日志（`curl -#`）

### 坑 3：gh CLI 在本地仓库会解析错仓库



* 现象：`gh release create` 报 "tag exists locally but has not been pushed to aiselp/AutoX"（解析到了 upstream）

* 对策：**所有 gh 命令显式加&#x20;**`-R sunc-Q/AutoX`

### 坑 4：中途失败会留下残缺资产



* 状态 `starter` 的资产是中断残留（可能 size 显示完整但 digest 为空），可安全删除：



```
curl -X DELETE -H "Authorization: Bearer \$TOKEN" \\

&#x20; https://api.github.com/repos/sunc-Q/AutoX/releases/assets/\<asset\_id>
```

### 坑 5：API 未认证会被限流



* 不带 token 请求 [api.github.com](https://api.github.com) → `API rate limit exceeded`

* 所有 API 调用带 `Authorization: Bearer $TOKEN`（token 用 `gh auth token -h github.com` 获取）

### 坑 6：draft Release 按 tag 查询 404



* `GET /releases/tags/v1.0.0` 查不到 draft；用 `GET /releases`（列表）拿 release\_id，再操作

## 三、常用命令速查



```
\# 获取 token（不显示明文）

TOKEN=\$(\~/bin/gh auth token -h github.com)

\# 查资产

curl -s -H "Authorization: Bearer \$TOKEN" \\

&#x20; https://api.github.com/repos/sunc-Q/AutoX/releases/\<release\_id>/assets

\# 发布 draft → public

curl -s -X PATCH -H "Authorization: Bearer \$TOKEN" \\

&#x20; https://api.github.com/repos/sunc-Q/AutoX/releases/\<release\_id> -d '{"draft":false}'

\# 单请求上传（可靠写法）

curl -sL -o /dev/null -w "%{http\_code}\n" -X POST -x http://127.0.0.1:7897 \\

&#x20; -m 7200 -H "Accept: application/vnd.github+json" \\

&#x20; -H "Authorization: Bearer \$TOKEN" \\

&#x20; -H "Content-Type: application/octet-stream" \\

&#x20; \--data-binary @app-suncV7-arm64-v8a-release.apk \\

&#x20; "https://uploads.github.com/repos/sunc-Q/AutoX/releases/\<release\_id>/assets?name=app-suncV7-arm64-v8a-release.apk"
```

## 四、基础设施备忘



| 项           | 值                                                                                      |
| ----------- | -------------------------------------------------------------------------------------- |
| gh CLI      | `~/bin/gh`（2.100.0，已认证 sunc-Q）                                                         |
| 发布 keystore | `~/keystores/sunc-release.jks`（别名 sunc，密码见 Gitee 备份）                                   |
| 签名配置        | `~/.gradle/gradle.properties`（SIGNING\_\*）                                             |
| 网络          | 必须走代理 `127.0.0.1:7897`；SSH 走 `~/.ssh/config`（[github.com:443](https://github.com:443)） |
| 本地 fork     | origin=`git@github.com:sunc-Q/AutoX.git`；upstream=`aiselp/AutoX`                       |