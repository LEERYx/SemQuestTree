package org.example;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * SemQuestTree HTTP API 的零依赖 Java 客户端示例（JDK 8+，无第三方库）。
 *
 * <p>接入自己的 Java 后端：把本文件与 {@link Json} 两个文件拷进工程即可，
 * 全部方法走 HTTP 调 SemQuestServer（默认 http://localhost:8787），
 * 决策模型凭据只存在于服务端 semquest.properties，客户端无需任何 key。</p>
 *
 * <p>main() 两种模式：无参=完整演示（建表 → 落位 → 检索 → 调门控 → 删表，结束自清理）；
 * 带 seed 参数=建 README 示例表 Driver Intents（与 docs/demo-en.gif 完全一致）并保留。
 * 两种模式都会对服务端当前后端发起真实判定请求（落位+检索约 19 次，建分支零判定）；
 * 想零成本跑，先在页面头部把后端切到 mock，或用 backend=mock 起一个临时服务。</p>
 *
 * <p>端点入出参详见 docs/API.md。</p>
 */
public final class SemQuestClient {

    private final String base;
    private final int timeoutMs;

    public SemQuestClient(String baseUrl) {
        this(baseUrl, 30000);
    }

    public SemQuestClient(String baseUrl, int timeoutMs) {
        String b = baseUrl;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.timeoutMs = timeoutMs;
    }

    // ── 表管理 ──────────────────────────────────────────────────────

    /** GET /api/tables → 全部表。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listTables() throws IOException {
        return (List<Map<String, Object>>) request("GET", "/api/tables", null);
    }

    /** POST /api/tables → 建表；mode 建表定死（noul 多选 / choice 单选）。 */
    public Map<String, Object> createTable(String name, String mode, String note) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":").append(Json.str(name)).append(",\"mode\":").append(Json.str(mode));
        if (note != null) sb.append(",\"note\":").append(Json.str(note));
        sb.append("}");
        return (Map<String, Object>) request("POST", "/api/tables", sb.toString());
    }

    /** PUT /api/tables?id= → 改表名/备注（传 null 的字段不改）。 */
    public Map<String, Object> updateTable(String id, String name, String note) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean f = true;
        if (name != null) { sb.append("\"name\":").append(Json.str(name)); f = false; }
        if (note != null) { sb.append(f ? "" : ",").append("\"note\":").append(Json.str(note)); }
        sb.append("}");
        return (Map<String, Object>) request("PUT", "/api/tables?id=" + enc(id), sb.toString());
    }

    /** DELETE /api/tables?id= → 删表（整棵树与落位数据）。 */
    public Map<String, Object> deleteTable(String id) throws IOException {
        return (Map<String, Object>) request("DELETE", "/api/tables?id=" + enc(id), null);
    }

    // ── 结构与节点 ──────────────────────────────────────────────────

    /** GET /api/t/&lt;id&gt;/meta → 表元信息（backend/mode/双门控/docCount）。 */
    public Map<String, Object> meta(String tableId) throws IOException {
        return (Map<String, Object>) request("GET", "/api/t/" + enc(tableId) + "/meta", null);
    }

    /** GET /api/t/&lt;id&gt;/tree → 全树结构（含 docCount，不含正文）。 */
    public Map<String, Object> tree(String tableId) throws IOException {
        return (Map<String, Object>) request("GET", "/api/t/" + enc(tableId) + "/tree", null);
    }

    /** GET /api/t/&lt;id&gt;/node?name= → 单层展开；nodeName 传 null 取 ROOT。 */
    public Map<String, Object> nodeLevel(String tableId, String nodeName) throws IOException {
        String q = nodeName == null ? "" : "?name=" + enc(nodeName);
        return (Map<String, Object>) request("GET", "/api/t/" + enc(tableId) + "/node" + q, null);
    }

    /** GET /api/t/&lt;id&gt;/leaf?name= → 节点详情（路径、判据、落位正文；内部节点含子树合计）。 */
    public Map<String, Object> leafInfo(String tableId, String nodeName) throws IOException {
        return (Map<String, Object>) request("GET",
                "/api/t/" + enc(tableId) + "/leaf?name=" + enc(nodeName), null);
    }

    /** GET /api/t/&lt;id&gt;/docs?node= → 节点直取（子树全部叶的落位内容，零模型调用；limit 可空，缺省 500）。 */
    public Map<String, Object> nodeDocs(String tableId, String nodeName, Integer limit) throws IOException {
        String q = "?node=" + enc(nodeName) + (limit == null ? "" : "&limit=" + limit);
        return (Map<String, Object>) request("GET", "/api/t/" + enc(tableId) + "/docs" + q, null);
    }

    /**
     * POST /api/t/&lt;id&gt;/node → 增长节点（会触发父节点存量逐条重判回填）。
     * noul 表 yes 必填、no 留空自动生成否定侧；choice 表 yes（选项描述）可空。
     */
    public Map<String, Object> addNode(String tableId, String parent, String name,
                                       String yes, String no) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"parent\":").append(Json.str(parent))
          .append(",\"name\":").append(Json.str(name));
        if (yes != null) sb.append(",\"yes\":").append(Json.str(yes));
        if (no != null) sb.append(",\"no\":").append(Json.str(no));
        sb.append("}");
        return (Map<String, Object>) request("POST", "/api/t/" + enc(tableId) + "/node", sb.toString());
    }

    /** PUT /api/t/&lt;id&gt;/node → 改节点（零模型调用）；newName/yes/no 传 null 表示不改。 */
    public Map<String, Object> editNode(String tableId, String name,
                                        String newName, String yes, String no) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":").append(Json.str(name));
        if (newName != null) sb.append(",\"newName\":").append(Json.str(newName));
        if (yes != null) sb.append(",\"yes\":").append(Json.str(yes));
        if (no != null) sb.append(",\"no\":").append(Json.str(no));
        sb.append("}");
        return (Map<String, Object>) request("PUT", "/api/t/" + enc(tableId) + "/node", sb.toString());
    }

    /** DELETE /api/t/&lt;id&gt;/node?name= → 删节点整个子树（含兜底叶，清落位指针）。 */
    public Map<String, Object> removeNode(String tableId, String name) throws IOException {
        return (Map<String, Object>) request("DELETE",
                "/api/t/" + enc(tableId) + "/node?name=" + enc(name), null);
    }

    // ── 判定 ────────────────────────────────────────────────────────

    /**
     * POST /api/t/&lt;id&gt;/add → 落位：一条语料沿判据下行落叶。
     * docId 传 null 自动生成；同 docId 重落 = 替换旧落位。
     */
    public Map<String, Object> add(String tableId, String content, String docId) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"content\":").append(Json.str(content));
        if (docId != null) sb.append(",\"id\":").append(Json.str(docId));
        sb.append("}");
        return (Map<String, Object>) request("POST", "/api/t/" + enc(tableId) + "/add", sb.toString());
    }

    /** POST /api/t/&lt;id&gt;/quest → 检索：命中叶 + 排序文档 + 判定轨迹（fastPath=true 表示同款查询缓存直出，零模型调用）。 */
    public Map<String, Object> quest(String tableId, String query) throws IOException {
        return (Map<String, Object>) request("POST", "/api/t/" + enc(tableId) + "/quest",
                "{\"query\":" + Json.str(query) + "}");
    }

    /** POST /api/t/&lt;id&gt;/addBatch → 批量导入（≤500 条；单条失败不牵连整批）。返回 added/failed/results。 */
    public Map<String, Object> addBatch(String tableId, String[][] docs) throws IOException {
        StringBuilder sb = new StringBuilder("{\"docs\":[");
        for (int i = 0; i < docs.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('{');
            if (docs[i][0] != null && !docs[i][0].trim().isEmpty()) {
                sb.append("\"id\":").append(Json.str(docs[i][0])).append(',');
            }
            sb.append("\"content\":").append(Json.str(docs[i][1])).append('}');
        }
        sb.append("]}");
        return (Map<String, Object>) request("POST", "/api/t/" + enc(tableId) + "/addBatch", sb.toString());
    }

    /** POST /api/t/&lt;id&gt;/gate → 调 noul 门控（只影响其后判定）。 */
    public Map<String, Object> setNoulGate(String tableId, double gate) throws IOException {
        return (Map<String, Object>) request("POST", "/api/t/" + enc(tableId) + "/gate",
                "{\"noulGate\":" + gate + "}");
    }

    /** POST /api/t/&lt;id&gt;/gate → 调 choice 采纳线（只影响其后判定）。 */
    public Map<String, Object> setChoiceGate(String tableId, double gate) throws IOException {
        return (Map<String, Object>) request("POST", "/api/t/" + enc(tableId) + "/gate",
                "{\"choiceGate\":" + gate + "}");
    }

    // ── 模型后端（全局） ────────────────────────────────────────────

    /** GET /api/model → 当前 provider/model。 */
    public Map<String, Object> currentModel() throws IOException {
        return (Map<String, Object>) request("GET", "/api/model", null);
    }

    /** POST /api/model → 热切换 provider（mock|jev|openrouter|bailian）与模型，零推理。 */
    public Map<String, Object> switchModel(String provider, String model) throws IOException {
        return (Map<String, Object>) request("POST", "/api/model",
                "{\"provider\":" + Json.str(provider) + ",\"model\":" + Json.str(model) + "}");
    }

    // ── HTTP 底座 ───────────────────────────────────────────────────

    /** 发请求并解析 JSON；非 2xx 抛 IOException（带服务端 error 文本）。 */
    private Object request(String method, String path, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (jsonBody != null) {
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            os.close();
        }
        int status = conn.getResponseCode();
        String body = readStream(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (status >= 400) {
            String msg = body;
            try {
                Object err = ((Map<?, ?>) Json.parse(body)).get("error");
                if (err != null) msg = String.valueOf(err);
            } catch (Exception ignore) {
                // 保留原始报文
            }
            throw new IOException("[HTTP " + status + "] " + msg);
        }
        if (body.isEmpty()) return null;
        return Json.parse(body);
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    // ── 演示 / 英文示例表 ────────────────────────────────────────────

    private static final String DEMO_TABLE = "Driver Intents";

    /**
     * 用法：
     * <pre>
     *   java -cp target/classes org.example.SemQuestClient            # 演示（建表→落位→检索→调门控→删表）
     *   java -cp target/classes org.example.SemQuestClient seed       # 建 README 示例表 Driver Intents 并保留
     *   java -cp target/classes org.example.SemQuestClient seed http://localhost:8790
     * </pre>
     * 两种模式都会对当前后端发起真实判定（seed 建表零判定，落位+检索约 19 次请求）；
     * 零成本跑请先把后端切到 mock。
     */
    public static void main(String[] args) throws IOException {
        boolean seed = args.length > 0 && "seed".equals(args[0]);
        String base = args.length > (seed ? 1 : 0) ? args[seed ? 1 : 0] : "http://localhost:8787";
        SemQuestClient c = new SemQuestClient(base);
        System.out.println("SemQuestTree 客户端 → " + base
                + "（对当前后端发起真实判定；零成本请先切 mock 后端）");

        String id = seedDriverIntents(c);

        // 检索：README/GIF 同款多标签查询 → 双叶命中
        Map<String, Object> rep = c.quest(id, "driver says the Fare is unpaid and the Cargo arrived damaged");
        System.out.println("检索: 「" + rep.get("query") + "」 命中叶=" + rep.get("matchedLeaves"));
        for (Object o : (List<Object>) rep.get("ranked")) {
            Map<String, Object> d = (Map<String, Object>) o;
            System.out.println("  " + d.get("docId") + " score=" + d.get("score")
                    + " via=" + d.get("viaLeaves") + " 正文=" + d.get("content"));
        }

        if (seed) {
            System.out.println("示例表已保留。打开 " + base + "/#/t/" + id + " 查看（同名表重跑即重建）。");
        } else {
            // 调门控演示 + 清理
            c.setNoulGate(id, 0.6);
            System.out.println("门控已调 0.6; 当前模型=" + c.currentModel().get("provider")
                    + "/" + c.currentModel().get("model"));
            c.deleteTable(id);
            System.out.println("演示完成，临时表已删除。要保留这张表请改用 seed 参数："
                    + "java -cp target/classes org.example.SemQuestClient seed");
        }
    }

    /** 建/重建 README 示例表 Driver Intents（判据与语料与 docs/demo-en.gif 完全一致），返回表 id。 */
    public static String seedDriverIntents(SemQuestClient c) throws IOException {
        for (Map<String, Object> t : c.listTables()) {
            if (DEMO_TABLE.equals(t.get("name"))) {
                c.deleteTable(String.valueOf(t.get("id")));
                System.out.println("清掉旧表: " + t.get("id"));
            }
        }
        Map<String, Object> t = c.createTable(DEMO_TABLE, "noul", "English demo table from the README");
        String id = String.valueOf(t.get("id"));
        System.out.println("建表: 「" + DEMO_TABLE + "」 mode=" + t.get("mode") + " → " + id);

        // 2. 分支（判据=事件级原子断言；节点名含语料关键词，mock 按名子串匹配也能命中）
        node(c, id, "ROOT", "Fare", "driver talks about the fare amount");
        node(c, id, "ROOT", "Cargo", "driver talks about cargo handling or condition");
        node(c, id, "ROOT", "Schedule", "driver talks about timing of the order");
        node(c, id, "ROOT", "Service", "driver talks about service experience or contact");
        node(c, id, "Fare", "surge", "driver asks for a higher fare or a surge");
        node(c, id, "Fare", "lowball", "driver complains the offered fare is too low");
        node(c, id, "Fare", "unpaid", "driver chases an unpaid fare");
        node(c, id, "Cargo", "damaged", "cargo arrived damaged");
        node(c, id, "Cargo", "loading", "argues about who does the loading");
        node(c, id, "Schedule", "late", "warns the delivery will be late");
        node(c, id, "Schedule", "cancel", "passenger or driver wants to cancel the order");
        node(c, id, "Service", "complaint", "complains about service attitude");
        node(c, id, "Service", "praise", "praises the passenger or the experience");
        node(c, id, "Service", "contact", "wants to reach the passenger or support");
        System.out.println("已加分支: Fare/Cargo/Schedule/Service × 9 叶");

        // 3. 批量落位 8 条（一次 addBatch；每条 2 层判定，单条失败不牵连整批）
        Map<String, Object> rep = c.addBatch(id, new String[][] {
            {"D-01", "Please add a surge to the Fare, that mountain detour took an hour."},
            {"D-02", "This Fare is a pure lowball for 40 km, please raise it."},
            {"D-03", "The ride is done but the Fare is still unpaid, I need that money."},
            {"D-04", "Half of the Cargo arrived damaged, boxes were crushed."},
            {"D-05", "No one helped with the Cargo loading, I carried it all alone."},
            {"D-06", "Schedule slip: bridge is jammed, delivery will be late by 30 minutes."},
            {"D-07", "Schedule change: the passenger wants to cancel the order."},
            {"D-08", "Great Service experience: big praise to this passenger, friendly and smooth pickup."},
        });
        System.out.println("批量落位: " + rep.get("added") + "/" + rep.get("total") + " 条");
        for (Object o : (List<?>) rep.get("results")) {
            Map<?, ?> r = (Map<?, ?>) o;
            System.out.println("  " + r.get("id") + " → " + r.get("leaves"));
        }
        return id;
    }

    private static void node(SemQuestClient c, String id, String parent, String name, String yes) throws IOException {
        Map<String, Object> rep = c.addNode(id, parent, name, yes, "");
        System.out.println("  +" + rep.get("node") + " (重判 " + rep.get("judged") + " 迁入 " + rep.get("moved") + ")");
    }
}
