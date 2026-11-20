package org.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动作追踪（viz 🔎 开关）：开关开启时记录落位/检索/生长全链路动作事件——决策模型批量判定
 * （含每分支判据与顺带的维度 score 题）、生成模型调用（判据生成/延伸生成，含完整请求体与
 * 响应体）、横向/纵向生长、存量重判验收、维度验收、回滚等。
 *
 * <p><b>按请求分组的动作流（session）</b>：每次外部请求（add/addBatch/quest/extend）由入口
 * {@link #beginSession} 开启一个会话，期间 {@link #record} 产生的事件全部归属该会话，
 * {@link #endSession} 结束时整段写入 traces/ 目录下的独立 JSON 文件（一请求一文件，tmp+move
 * 原子落盘，重启不丢）；viz 动作流页按会话列表展示，单开一个请求可看其中完整动作流去追溯。
 * 内存仅保留最近 {@link #SESSION_CAP} 个会话元数据索引，事件本体以文件为准。
 *
 * <p>全局环形缓冲 BUF（{@link #CAP} 条）保留作表级增量拉取（GET /api/t/&lt;id&gt;/trace?since=N）
 * 的兼容通道；单条含请求/响应体可达数十 KB，超出淘汰最旧。关闭开关时 record 零开销直接丢弃，
 * 服务重启清空且回默认关（已落盘的会话文件不受影响，仍可单开查看）。
 *
 * <p>类型体系（type 前缀约定）：llm.* = 模型调用（reqBody/respBody 就位）；act.* = 语义动作
 * （detail 携带节点/判据/doc 等关键信息）；新增动作类型直接扩前缀，前端按映射表渲染、未知类型
 * 兜底显示。预留：act.lift（升维上提）/ act.sink（降维下沉，升降维重构未实现）。
 */
public final class Trace {

    /** 单条动作事件。 */
    public static final class Event {
        public final long seq;          // 全局递增序号（增量拉取游标）
        public final long ts;           // 产生时刻（epoch ms）
        public final String tableId;    // 所属表（跨表过滤；全局工具产生的为 null）
        public final String type;       // 类型标识（llm.* / act.*）
        public final String title;      // 展示标题（一句话概括）
        public final Map<String, Object> detail;   // 结构化关键信息（节点/判据/doc/概率…）
        public final String reqBody;    // 模型调用完整请求体（仅 llm.* 事件，act.* 为 null）
        public final String respBody;   // 模型调用完整响应体（仅 llm.* 事件，act.* 为 null）
        public final int inTok;         // 输入 token（llm.* 事件）
        public final int outTok;        // 输出 token（llm.* 事件）
        public final long ms;           // 耗时（模型调用为端到端墙钟；动作为自身处理耗时）

        Event(long seq, long ts, String tableId, String type, String title,
              Map<String, Object> detail, String reqBody, String respBody,
              int inTok, int outTok, long ms) {
            this.seq = seq;
            this.ts = ts;
            this.tableId = tableId;
            this.type = type;
            this.title = title;
            this.detail = detail;
            this.reqBody = reqBody;
            this.respBody = respBody;
            this.inTok = inTok;
            this.outTok = outTok;
            this.ms = ms;
        }

        /** 序列化为响应 JSON 的键值（req/resp 体大，仅在时就位）。 */
        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("seq", seq);
            m.put("ts", ts);
            m.put("tableId", tableId);
            m.put("type", type);
            m.put("title", title);
            if (detail != null && !detail.isEmpty()) m.put("detail", detail);
            if (reqBody != null) m.put("reqBody", reqBody);
            if (respBody != null) m.put("respBody", respBody);
            if (inTok > 0 || outTok > 0) {
                m.put("inTok", inTok);
                m.put("outTok", outTok);
            }
            if (ms > 0) m.put("ms", ms);
            return m;
        }
    }

    /**
     * 一次外部请求的动作流会话：请求期间产生的全部事件归属本会话，结束（endSession）时
     * 整段写入独立文件；内存索引仅存元数据（不含事件体）。
     */
    public static final class Session {
        public final long seq;          // 会话序号（与首条事件游标对齐）
        public final long ts;           // 会话开始时刻（epoch ms）
        public final String tableId;    // 所属表（后台任务/全局动作为 null）
        public final String action;     // 请求动作（add / addBatch / quest / extend…）
        public String title;            // 摘要标题（结束时由最后事件/调用方补写）
        public volatile boolean rolledBack = false;   // 已被回退（viz 列表标记，回退按钮消失）
        volatile int sumIn = 0;         // 会话内模型输入 token 合计（record 归属时累加）
        volatile int sumOut = 0;        // 会话内模型输出 token 合计
        final List<Event> events = new ArrayList<Event>();   // 会话内全部事件（旧→新）
        String file;                    // 落盘文件名（endSession 时定；未落盘为 null）

        Session(long seq, long ts, String tableId, String action, String title) {
            this.seq = seq;
            this.ts = ts;
            this.tableId = tableId;
            this.action = action;
            this.title = title;
        }

        /** 会话元数据（不含事件体；viz 请求列表用）。 */
        public Map<String, Object> metaMap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("seq", seq);
            m.put("ts", ts);
            m.put("tableId", tableId);
            m.put("action", action);
            m.put("title", title);
            m.put("events", events.size());
            if (file != null) m.put("file", file);
            if (rolledBack) m.put("rolledBack", true);
            if (sumIn > 0 || sumOut > 0) {
                m.put("tokens", sumIn + sumOut);   // 会话总 token（决策+生成全链路合计）
                m.put("inTok", sumIn);
                m.put("outTok", sumOut);
            }
            return m;
        }

        /** 会话全量（元数据 + 事件流；单开会话与落盘共用）。 */
        Map<String, Object> fullMap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("seq", seq);
            m.put("ts", ts);
            m.put("tableId", tableId);
            m.put("action", action);
            m.put("title", title);
            if (file != null) m.put("file", file);
            if (rolledBack) m.put("rolledBack", true);
            if (sumIn > 0 || sumOut > 0) {
                m.put("tokens", sumIn + sumOut);
                m.put("inTok", sumIn);
                m.put("outTok", sumOut);
            }
            List<Object> evs = new ArrayList<Object>();
            for (Event e : events) evs.add(e.toMap());
            m.put("events", evs);
            return m;
        }
    }

    private static final int CAP = 300;              // 全局环形缓冲上限（增量拉取兼容通道）
    private static final int SESSION_CAP = 50;       // 内存会话索引上限（事件本体在文件）
    private static final Path DIR = Paths.get("traces");   // 会话存档目录（工作区根，不入 git）

    private static final ArrayDeque<Event> BUF = new ArrayDeque<Event>(CAP);
    private static final ArrayDeque<Session> SESSIONS = new ArrayDeque<Session>(SESSION_CAP);
    private static volatile boolean enabled = false;
    private static long seqGen = 0;
    private static long sessionGen = 0;

    /** 当前线程归属的动作流会话（请求入口 begin，finally end；异步任务线程同样适用）。 */
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<Session>();

    private Trace() { }

    /** 开关有效值（埋点与捕获共用判断）。 */
    public static boolean isEnabled() {
        return enabled;
    }

    /** 开/关（viz 🔎 热切换；内存态，重启回关——已落盘会话文件保留可查）。 */
    public static void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        System.out.println("[追踪] 动作流已" + (on ? "开启（按请求分组记录，一请求一个动作流文件存档 traces/）"
                : "关闭（埋点零开销）"));
    }

    /**
     * 开启一次请求的动作流会话（开关关闭时返回 null，endSession 兼容 null）。
     * 调用方在请求入口 begin、finally end；同线程内的全部 record 自动归属本会话。
     */
    public static Session beginSession(String tableId, String action, String title) {
        if (!enabled) return null;
        Session s = new Session(++sessionGen, System.currentTimeMillis(), tableId, action, title);
        CURRENT.set(s);
        return s;
    }

    /**
     * 结束当前会话：title 未定时回退最后一条事件标题；整段写入 traces/ 独立文件（原子替换），
     * 内存索引保留最近 SESSION_CAP 个会话元数据。开关中途关闭也照常落盘已收集的事件。
     */
    public static void endSession(Session s) {
        if (s == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.remove();
        synchronized (Trace.class) {
            if (s.title == null || s.title.trim().isEmpty()) {
                s.title = s.events.isEmpty() ? "(无事件)" : s.events.get(s.events.size() - 1).title;
            }
            s.file = "trace-" + String.format("%06d", s.seq) + "-" + s.action + ".json";
            writeFile(s);
            SESSIONS.addLast(s);
            while (SESSIONS.size() > SESSION_CAP) SESSIONS.pollFirst();
        }
    }

    /** 会话整段落盘：traces/&lt;file&gt;.tmp 先写后 move 原子替换（崩溃不损坏旧档）。 */
    private static void writeFile(Session s) {
        try {
            Files.createDirectories(DIR);
            Path target = DIR.resolve(s.file);
            Path tmp = DIR.resolve(s.file + ".tmp");
            Files.write(tmp, Json.write(s.fullMap()).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tmp, target);   // 同名旧档存在且平台不支持 REPLACE 时回退
            }
        } catch (IOException e) {
            System.out.println("[追踪] 会话存档写盘失败: " + s.file + " - " + e.getMessage());
            s.file = null;   // 落盘失败：会话仅存内存索引（下次重启丢失）
        }
    }

    /**
     * 记录一条事件（开关关闭时零开销返回 null）：进全局环形缓冲（增量拉取兼容），
     * 同时归属当前线程会话（无会话时不入会话，仅走全局缓冲）。
     */
    public static Event record(String tableId, String type, String title,
                               Map<String, Object> detail, String reqBody, String respBody,
                               int inTok, int outTok, long ms) {
        if (!enabled) return null;
        Event e = new Event(++seqGen, System.currentTimeMillis(), tableId, type, title,
                detail, reqBody, respBody, inTok, outTok, ms);
        synchronized (Trace.class) {
            BUF.addLast(e);
            while (BUF.size() > CAP) BUF.pollFirst();
            Session cur = CURRENT.get();
            if (cur != null) {
                cur.events.add(e);
                cur.sumIn += e.inTok;   // 会话级 token 合计（viz 列表/弹层展示）
                cur.sumOut += e.outTok;
            }
        }
        return e;
    }

    /** 会话元数据列表（旧→新；viz 请求列表用）。 */
    public static synchronized List<Session> sessions() {
        return new ArrayList<Session>(SESSIONS);
    }

    /**
     * 单开一个会话：优先按文件名从存档读（重启后仍可查），文件缺失回退内存索引。
     * @param file 会话文件名（仅接受 trace-*.json 形态，防路径穿越）
     * @return 会话全量 map；不存在返回 null
     */
    public static Map<String, Object> readSession(String file) {
        if (file == null || !file.startsWith("trace-") || !file.endsWith(".json")
                || file.contains("/") || file.contains("\\") || file.contains("..")) {
            return null;
        }
        Path p = DIR.resolve(file);
        if (Files.exists(p)) {
            try {
                return Json.parseObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.out.println("[追踪] 会话存档读取失败: " + file + " - " + e.getMessage());
            }
        }
        synchronized (Trace.class) {
            for (Session s : SESSIONS) {
                if (file.equals(s.file)) return s.fullMap();
            }
        }
        return null;
    }

    /** 存档目录内的全部会话文件名（旧→新；重启后内存索引为空，列表从文件恢复）。 */
    public static synchronized List<Map<String, Object>> archivedSessions() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (!Files.isDirectory(DIR)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(DIR, "trace-*.json")) {
            for (Path p : ds) {
                try {
                    Map<String, Object> m = Json.parseObject(
                            new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                    LinkedHashMap<String, Object> meta = new LinkedHashMap<String, Object>();
                    meta.put("seq", m.get("seq"));
                    meta.put("ts", m.get("ts"));
                    meta.put("tableId", m.get("tableId"));
                    meta.put("action", m.get("action"));
                    meta.put("title", m.get("title"));
                    Object evs = m.get("events");
                    meta.put("events", evs instanceof List ? ((List<?>) evs).size() : 0);
                    meta.put("file", p.getFileName().toString());
                    if (Boolean.TRUE.equals(m.get("rolledBack"))) meta.put("rolledBack", true);
                    Object tk = m.get("tokens");
                    if (tk instanceof Number && ((Number) tk).longValue() > 0) {
                        meta.put("tokens", tk);
                        meta.put("inTok", m.get("inTok"));
                        meta.put("outTok", m.get("outTok"));
                    }
                    out.add(meta);
                } catch (Exception ignore) {
                    // 单个坏档跳过，不影响列表
                }
            }
        } catch (IOException e) {
            System.out.println("[追踪] 会话存档目录读取失败: " + e.getMessage());
        }
        out.sort((a, b) -> Long.compare(a.get("seq") instanceof Number ? ((Number) a.get("seq")).longValue() : 0,
                b.get("seq") instanceof Number ? ((Number) b.get("seq")).longValue() : 0));
        return out;
    }

    /** 增量拉取：seq &gt; sinceSeq 且属于该表的事件，旧→新排序。tableId 为 null 不过滤（全表流）。 */
    public static synchronized List<Event> since(String tableId, long sinceSeq) {
        List<Event> out = new ArrayList<Event>();
        for (Event e : BUF) {
            if (e.seq > sinceSeq && (tableId == null || tableId.equals(e.tableId))) out.add(e);
        }
        return out;
    }

    /** 当前最大 seq（viz 增量游标起点）。 */
    public static synchronized long maxSeq() {
        return seqGen;
    }

    /**
     * 标记某会话已被回退（viz 列表显「已回退」徽章、按钮消失）：内存索引同步置位；
     * 存档文件按 trace-&lt;seq 六位&gt;-*.json 前缀定位，读入后写 rolledBack 标记原子落盘。
     */
    public static synchronized void markRolledBackBySeq(long seq) {
        synchronized (Trace.class) {
            for (Session s : SESSIONS) {
                if (s.seq == seq) s.rolledBack = true;
            }
        }
        if (!Files.isDirectory(DIR)) return;
        String prefix = "trace-" + String.format("%06d", seq) + "-";
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(DIR, prefix + "*.json")) {
            for (Path p : ds) {
                try {
                    Map<String, Object> m = Json.parseObject(
                            new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                    m.put("rolledBack", true);
                    Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
                    Files.write(tmp, Json.write(m).getBytes(StandardCharsets.UTF_8));
                    Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    System.out.println("[追踪] 回退标记写盘失败: " + p.getFileName() + " - " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.out.println("[追踪] 回退标记定位失败: " + e.getMessage());
        }
    }
}
