# Elasticsearch Kibana Elastic Agent 安装与测试

前提：Mac 上的 Docker Desktop 已运行。这里只安装这三个组件，统一使用项目原定的 9.5.3 版本。采用本地无密码配置，端口仅对本机开放。

## 1 进入准备好的配置目录

两个完整配置文件已经放在项目的 `elastic-local` 文件夹：

- `compose.yml`：下载和运行 Elasticsearch、Kibana、Elastic Agent。
- `elastic-agent.yml`：让 Agent 采集日志并发送到 Elasticsearch，采用 standalone 模式，不需要 Fleet Server。

打开终端，执行：

```bash
cd /Volumes/ulinsz/chatgpt-projects/size-elk/elastic-local
mkdir -p ../logs
chmod 644 elastic-agent.yml
docker compose config -q
```

最后一条没有报错即可继续。后面的命令都在这个目录执行。若之前已经启动了其他 ES/Kibana，先停止占用 9200/5601 的那套服务。

## 2 下载这三个软件

```bash
docker compose pull
```

等待三个镜像下载完成。这就是 Docker 方式的安装，不需要另下 Mac 安装包。

## 3 启动 Elasticsearch 和 Kibana

```bash
docker compose up -d elasticsearch kibana
curl -fsS http://localhost:9200
```

ES 返回包含 `version` 的 JSON 就表示启动成功。暂时连接失败时，等待片刻再执行 curl。

浏览器打开 **[Kibana](http://localhost:5601)**，等到首页出现。当前配置无需输入账号、密码或 enrollment token。

## 4 启动 Elastic Agent

```bash
docker compose up -d elastic-agent
docker compose ps
docker compose logs --tail=50 elastic-agent
```

三个容器都应处于运行状态，ES 显示 healthy。Agent 读取项目根目录 `logs` 下的 JSON 文件；容器内对应路径为 `/logs`。

## 5 写入测试日志

复制执行下面整段命令，生成 30 条测试日志：

```bash
for i in {1..30}; do
  printf '{"@timestamp":"%s","log.level":"INFO","service.name":"install-test","message":"elastic install test %s"}\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$i"
done >> ../logs/install-test.json
```

一次写多条是为了超过文件采集的默认指纹长度门槛，避免短文件迟迟不被采集。等待约 10～30 秒，然后查询：

```bash
curl -fsS 'http://localhost:9200/logs-demo.microservices-local/_search?pretty&size=1&q=service.name:install-test'
```

**返回结果中 `hits.total.value` 大于 0，且能看到 `elastic install test`，就说明 Agent → ES 已打通。** 如果首次返回 404，稍后重试；持续失败可用 `docker compose logs --tail=100 elastic-agent` 查看原因。

## 6 在 Kibana 中查看

1. 打开 [http://localhost:5601](http://localhost:5601)。
2. 用顶部搜索找到 **Data Views**，点击 **Create data view**。
3. Name 填 `microservices`，Index pattern 填 `logs-demo.microservices-*`。
4. Timestamp field 选 `@timestamp`，保存。
5. 进入 **Discover**，选择 `microservices`，时间范围设为 **Last 15 minutes**。
6. 查询栏输入 `service.name : "install-test"`，刷新。

**看到测试日志，表示三个软件的安装和采集测试全部通过。** standalone Agent 无须出现在 Fleet 的 Agents 列表中。

## 7 接入你已经写好的项目

先保持上面的测试配置不变，将业务日志接入同一个目录。日志必须是**每行一个 JSON 对象**，文件名以 `.json` 结尾；只打印到控制台不会被此配置采集。

- **项目在 IDE 中运行：** 三个服务已配置 `LOG_FILE`，默认写入 `./logs/<服务名>.json`。将各服务运行配置的 Working directory 设为 `/Volumes/ulinsz/chatgpt-projects/size-elk` 即可。也可以通过环境变量 `LOG_FILE` 指定完整日志文件路径。日志目录为 `/Volumes/ulinsz/chatgpt-projects/size-elk/logs`。例如订单日志写到该目录的 `order-service.json`。
- **项目在 Docker 中运行：** 在业务服务的 Compose 中，将下面这一行加入各服务的 `volumes`，让业务日志目录 `/logs` 指向同一个 Mac 目录，然后重新创建业务容器。若已有挂载到 `/logs` 的配置，替换那一行。

```yaml
volumes:
  - /Volumes/ulinsz/chatgpt-projects/size-elk/logs:/logs
```

启动业务并请求接口；沿用原项目接口时，可执行：

```bash
curl -i http://localhost:8088/api/orders/1001
```

在 Discover 中查询 `service.name : "order-service"`。首次日志文件需要积累超过约 1 KB，可多请求几次。若应用报日志目录不可写，调整目录权限以允许业务进程用户写入。

## 常用命令

```bash
# 停止，保留 ES 数据和采集进度
docker compose down

# 下次启动
docker compose up -d

# 查看错误
docker compose logs --tail=100 elasticsearch kibana elastic-agent
```

不要在日常停止时添加 `-v`，它会删除数据卷。若遇到外置磁盘 `Mounts denied`，在 Docker Desktop 的 File sharing 中允许访问本配置目录。

配置参考：[Elastic Agent 容器运行](https://www.elastic.co/docs/reference/fleet/elastic-agent-container)、[standalone 日志采集](https://www.elastic.co/docs/solutions/observability/logs/stream-any-log-file)。
