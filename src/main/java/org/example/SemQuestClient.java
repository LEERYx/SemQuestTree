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
 * <p>main() 是完整演示：建表 → 加分支 → 落位 → 检索 → 调门控 → 删表。
 * 注意 add/quest 会对服务端当前后端发起真实判定请求（约 6 次）；
 * 想零成本跑演示，先在页面头部把后端切到 mock，或用 backend=mock 起一个临时服务。</p>
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

    /** POST /api/t/&lt;id&gt;/quest → 检索：命中叶 + 排序文档 + 判定轨迹。 */
    public Map<String, Object> quest(String tableId, String query) throws IOException {
        return (Map<String, Object>) request("POST", "/api/t/" + enc(tableId) + "/quest",
                "{\"query\":" + Json.str(query) + "}");
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

    // ── 演示 ────────────────────────────────────────────────────────

    /** 用法：java -cp target/classes org.example.SemQuestClient [baseUrl] */
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws IOException {
        String base = args.length > 0 ? args[0] : "http://localhost:8787";
        SemQuestClient c = new SemQuestClient(base);
        System.out.println("SemQuestTree 客户端演示 → " + base
                + "（add/quest 会对当前后端发起真实判定，约 6 次请求；零成本演示请先把后端切到 mock）");

        // 表名固定，先清掉上一次的演示表，保证可重复运行
        String name = "客户端演示";
        for (Map<String, Object> t : c.listTables()) {
            if (name.equals(t.get("name"))) {
                c.deleteTable(String.valueOf(t.get("id")));
                System.out.println("清掉上次演示表: " + t.get("id"));
            }
        }

        // 1. 建表（noul 多选分支）
        Map<String, Object> t = c.createTable(name, "noul", "由 SemQuestClient.main 自动创建");
        String id = String.valueOf(t.get("id"));
        System.out.println("建表: 「" + name + "」 mode=" + t.get("mode") + " → " + id);

        // 2. 加分支（判据用事件级原子断言；分支名含正文关键词，mock 后端按名子串匹配也能命中）
        c.addNode(id, "ROOT", "退钱", "用户明确要求退钱、退款或退货", "");
        c.addNode(id, "ROOT", "发货", "用户催促尽快发货或催查物流进度", "");
        System.out.println("已加分支: 退钱 / 发货");

        // 3. 落位
        Map<String, Object> a1 = c.add(id, "我要退钱，不然就去投诉你们", "Z-001");
        System.out.println("落位 Z-001 → " + a1.get("leaves"));
        Map<String, Object> a2 = c.add(id, "三天了还没发货，到底什么时候发？", "Z-002");
        System.out.println("落位 Z-002 → " + a2.get("leaves"));

        // 4. 检索
        Map<String, Object> rep = c.quest(id, "有没有人要求退钱的");
        System.out.println("检索: 「" + rep.get("query") + "」 命中叶=" + rep.get("matchedLeaves"));
        for (Object o : (List<Object>) rep.get("ranked")) {
            Map<String, Object> d = (Map<String, Object>) o;
            System.out.println("  " + d.get("docId") + " score=" + d.get("score")
                    + " via=" + d.get("viaLeaves") + " 正文=" + d.get("content"));
        }

        // 5. 调门控并看当前模型
        c.setNoulGate(id, 0.6);
        System.out.println("门控已调 0.6; 当前模型=" + c.currentModel().get("provider")
                + "/" + c.currentModel().get("model"));

        // 6. 清理
        c.deleteTable(id);
        System.out.println("演示完成，临时表已删除。拷 SemQuestClient.java + Json.java 进你的后端即可调用以上全部方法。");
    }
}
