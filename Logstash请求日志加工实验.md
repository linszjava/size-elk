# Logstash 请求日志加工实验

## 1 这次要解决什么问题

当前 `/api/orders/1003` 返回 404，但网关记录的是 INFO；只筛选 ERROR 会漏掉失败请求。这次引入 Logstash，把网关的请求完成日志转为可以统一分析的 HTTP 事件。

链路变为：

```text
Java 服务 → 项目 logs 文件夹 → Elastic Agent（Filebeat）
                                      ↓
                                  Logstash
                        补字段、转换单位、标记请求结果
                                      ↓
                              Elasticsearch → Kibana
```

本实验使用已有的三个 Java 服务、ES、Kibana、Agent，仅新增 Logstash 9.5.3。Nacos 和 Java 服务仍按现有方式运行。Docker 需要额外可用内存，Logstash 本配置的堆为 512 MB，实际占用会更高。

## 2 处理前后有什么区别

以网关记录的一次 404 为例，下面省略其他字段：

处理前：

```json
{"requestId":"demo-404","path":"/api/orders/1003","status":404,"duration_ms":77}
```

处理后保留上述字段，并增加：

```json
{
  "http": {
    "request": {"id":"demo-404"},
    "response": {"status_code":404}
  },
  "url": {"path":"/api/orders/1003"},
  "event": {
    "action":"gateway_request_completed",
    "outcome":"failure",
    "duration":77000000
  },
  "tags":["http_4xx"],
  "labels":{"processing_pipeline":"logstash_requests_v1"}
}
```

`event.duration` 的 ECS 单位是纳秒，77 毫秒乘以 1,000,000。`log.level` 保持 INFO：日志级别和 HTTP 请求是否成功是两个概念。

| 原始字段或条件 | Logstash 的处理 |
|---|---|
| `requestId` | 复制到 `http.request.id`，保留原字段，现有查询继续有效 |
| 网关日志存在 `status` | 作为请求完成事件，增加 `event.action` |
| `status` | 复制到 `http.response.status_code` 并转为整数 |
| `path` | 复制到 `url.path` |
| `duration_ms` | 转换为纳秒，写入 `event.duration` |
| 状态码 200～399 | 标记 `success`，添加 `http_success` 标签 |
| 状态码 400～499 | 标记 `failure`，添加 `http_4xx` 标签 |
| 状态码 500 及以上 | 标记 `failure`，添加 `http_5xx` 标签 |
| 其他状态，如 0 | 标记 `unknown` |
| 所有经过该管道的日志 | 添加 `labels.processing_pipeline`，证明经过了 Logstash |

此处 outcome 是按“网关请求是否成功”的业务口径定义。404 不会被改成 ERROR，Logstash 也不会凭空生成异常原因。

## 3 完整搭建配置

v-1.2 只使用一套配置，默认通过 Logstash 写入 ES：

```text
size-elk/
├── logs/                              Java 服务写日志
└── elastic-local/
    ├── compose.yml                    一次启动四个组件
    ├── elastic-agent.yml              读取文件，发送到 Logstash
    └── logstash/pipeline/requests.conf 接收、加工、发送到 ES
```

四个组件统一使用 9.5.3。打开项目中的文件即可查看完整配置，不需要合并 Compose，也不需要额外启动脚本。

| 服务 | 作用 | 地址 |
|---|---|---|
| elasticsearch | 保存加工后的日志 | Mac 访问 http://localhost:9200 |
| kibana | 查询与展示 | 浏览器访问 http://localhost:5601 |
| logstash | 补充字段、转换耗时、分类请求 | Docker 内网 logstash:5044 |
| elastic-agent | 读取项目 logs 下的 JSON 文件 | 无需浏览器访问 |

Agent 的输出固定为：

```yaml
outputs:
  default:
    type: logstash
    hosts:
      - logstash:5044
```

Agent 在 Compose 中通过 `command: ["-c", "/etc/elastic-agent/elastic-agent.yml"]` 显式读取挂载的配置。这样即使状态卷保留了旧的直连 ES 配置，也不会覆盖本次配置；无需删除采集进度。

Logstash 的 `requests.conf` 包含三段：`input` 在 5044 接收 Agent 数据，`filter` 执行第 2 节的规则，`output` 发到 `http://elasticsearch:9200`。5044 是采集协议端口，不是 HTTP 页面，不需要映射到 Mac。

数据仍写入 `logs-demo.microservices-local`。Agent 的日志挂载是 `../logs:/logs:ro`；三个 Java 服务从 size-elk 根目录启动，继续使用已有 `./logs/<服务名>.json` 配置。

本实验使用自定义 filestream 数据集，并保留原有字段。以后使用依赖 ingest pipeline 的官方 Integration 时，需遵循该 Integration 的处理要求。[Agent 输出说明](https://www.elastic.co/docs/reference/fleet/logstash-output)

## 4 下载并启动整套环境

前提：Docker Desktop 已运行，Nacos 和三个 Java 服务使用现有配置。本节只搭建 ES、Kibana、Logstash、Agent；确保 Docker 有足够可用内存，建议为整套本地实验分配约 8 GB。

### 下载镜像并检查配置

```bash
cd /Volumes/ulinsz/chatgpt-projects/size-elk/elastic-local
mkdir -p ../logs
docker compose config -q
docker compose pull
```

检查 Logstash 管道语法：

```bash
docker compose run --rm --no-deps logstash \
  bin/logstash --config.test_and_exit -f /usr/share/logstash/pipeline/requests.conf
```

看到 `Configuration OK` 且命令成功退出后继续。它只验证配置语法，不证明已经采集到日志。

### 启动四个组件

```bash
docker compose up -d --force-recreate
docker compose ps
docker compose logs --tail=100 logstash elastic-agent
```

`--force-recreate` 确保之前已运行的容器也重新读取本版本配置。它会重建容器，但保留命名数据卷；已运行的 ES、Kibana 会短暂重启。全新搭建也可直接使用这条命令。

Compose 先等待 ES 健康，再启动相关组件。Agent 在 Logstash 容器启动后启动，Logstash 的内部管道就绪仍需时间，期间短暂连接失败会重试。不要仅凭容器显示 running 判断完成。

成功标志：

1. `docker compose ps` 中四个服务都在运行，ES 显示 healthy。
2. Logstash 日志出现 `Pipeline started` 及监听 5044 的信息，没有持续报错。
3. 下面的 ES 请求返回版本 JSON；浏览器能打开 Kibana。

```bash
curl -fsS http://localhost:9200
```

Kibana 地址：[http://localhost:5601](http://localhost:5601)。本地配置无密码，仅绑定本机端口。

已有 ES 数据和 Agent 采集进度会保留，历史日志不会自动增加新字段。接下来必须发起新请求来验证 Logstash。

## 5 发送成功与失败请求

确保网关、订单、库存三个服务已运行并注册到 Nacos。生成本轮唯一编号，连续测试 10 轮：

```bash
RUN_ID="ls-lab-$(date +%s)"
for i in {1..10}; do
  curl -sS -o /dev/null -w '商品1001 HTTP %{http_code}\n' \
    -H "X-Request-ID: ${RUN_ID}-ok-${i}" \
    http://localhost:8088/api/orders/1001
  curl -sS -o /dev/null -w '商品1003 HTTP %{http_code}\n' \
    -H "X-Request-ID: ${RUN_ID}-missing-${i}" \
    http://localhost:8088/api/orders/1003
done
printf '本轮查询前缀：%s\n' "$RUN_ID"
```

预期：1001 返回 200，1003 返回 404。多次请求也能让新日志文件超过采集指纹长度门槛。等待约 10～30 秒后查看 Kibana。

## 6 在 Kibana 证明 Logstash 生效

打开 Discover，选择原来的 Data View，时间范围设为最近 15 分钟。清除之前的 requestId、INFO 等筛选。

先查经过 Logstash 的日志：

```text
labels.processing_pipeline : "logstash_requests_v1"
```

展开最新一条网关完成日志，检查新增字段。也可在终端查询，不依赖 Kibana 展示配置：

```bash
curl -fsS 'http://localhost:9200/logs-demo.microservices-local/_search?pretty&size=1&sort=@timestamp:desc&q=labels.processing_pipeline:logstash_requests_v1'
```

再查失败的请求完成事件：

```text
labels.processing_pipeline : "logstash_requests_v1" AND event.action : "gateway_request_completed" AND event.outcome : "failure"
```

只查 404：

```text
labels.processing_pipeline : "logstash_requests_v1" AND http.response.status_code : 404
```

查看一次请求的全过程，例如把编号换成本轮输出的实际值：

```text
requestId : "ls-lab-实际编号-missing-1"
```

保留 `requestId` 查询时不要同时筛选 `event.action`，否则会隐藏订单和库存日志。

添加以下展示列：`@timestamp`、`service.name`、`message`、`requestId`、`http.response.status_code`、`event.outcome`、`duration_ms`、`tags`。

同一请求的原始本地 JSON 中没有 processing_pipeline 标记，而 ES 中有，就能直观看到 Logstash 对数据的加工。它不会回写修改本地日志文件。

## 7 可选做一张请求结果图

在 Kibana 的 Lens 中选择相同 Data View，筛选：

```text
labels.processing_pipeline : "logstash_requests_v1" AND event.action : "gateway_request_completed"
```

选择柱状图，横轴用 `@timestamp` 时间直方图，纵轴用记录数，按 `event.outcome` 拆分颜色，保存为“网关请求结果”。只统计完成日志，避免把一次请求的多条日志都算成请求次数。

若只看本轮，可进一步缩小时间范围或通过 requestId 字段筛选。本轮无重试、无其他请求时，预期为成功 10 次、失败 10 次；有重复投递时应按 requestId 去重再统计。

## 8 常用操作和排查

所有命令都在 `elastic-local` 目录执行：

```bash
# 后续日常启动
docker compose up -d

# 停止并保留数据
docker compose down

# 查看日志
docker compose logs --tail=150 elastic-agent logstash

# 修改 Logstash 规则后应用
docker compose restart logstash

# 修改 Agent 配置后应用
docker compose restart elastic-agent
```

| 现象 | 检查 |
|---|---|
| 旧日志没有新字段 | 正常；发送新请求，查最新时间段 |
| Agent 提示 connection refused | 确认 Logstash 管道已启动并监听 5044 |
| Logstash 报 400 或字段类型冲突 | 查看输出错误和已有 ES mapping |
| 查不到日志 | 检查日志是否写入项目 logs、时间范围，以及采集器日志 |
| 新环境没有 Data View | 创建 `logs-demo.microservices-*`，时间字段选 `@timestamp` |
| 容器退出 137 | 检查 Docker 内存 |

不要删除 `agent-state` 卷或执行 `down -v`；前者会丢失采集进度，后者会删除此项目的数据卷。日志投递可能因重试重复，本实验不保证 exactly-once。Logstash 未启用持久化队列，不能据此保证故障时零数据丢失。

## 9 版本关系

- `v-1.1`：Agent 直接连接 ES，保留在 Git 分支中。
- `v-1.2`：本指南的完整四组件方案，Agent 默认连接 Logstash。

日常运行本版本不需要切换链路。若需要回到旧版本，先在当前版本执行 `docker compose down`，确认代码修改已保存，再从项目根目录执行 `git switch v-1.1`，进入 `elastic-local` 执行 `docker compose up -d`。两版使用相同项目名及已有 ES、Agent 数据卷；旧版无需启动 Logstash，历史加工记录仍可查询。

参考：[Logstash Agent 输入](https://www.elastic.co/docs/reference/logstash/plugins/plugins-inputs-elastic_agent)、[ES 数据流输出](https://www.elastic.co/docs/reference/logstash/plugins/plugins-outputs-elasticsearch)、[Logstash 9.5.3 镜像](https://www.docker.elastic.co/r/logstash/logstash:9.5.3)。
