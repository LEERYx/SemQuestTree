package org.example;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 提示词装载器：semquest.properties 的 prompt.* / growth.prompt* 键值 = 提示词文件路径
 * （相对进程工作目录或绝对路径），文件内容即提示词模板正文（UTF-8 纯文本，占位符由调用方替换）。
 *
 * <p>代码内不保留任何内置提示词文本与默认回退：必配键路径未配置、文件不可读或内容空白即抛
 * IllegalStateException，由调用方决定 fail-fast（服务启动）或保留旧值（配置热载）。
 *
 * <p>必配键：prompt.rule.{add,search}、prompt.noul.add.instructions、prompt.noul.add.criterion、
 * prompt.noul.search.criterion、prompt.noul.search.query、prompt.noul.no.{han,latin}、
 * prompt.choice.{add.root,add.node,other}；
 * 可选分野键：prompt.noul.search.instructions、prompt.choice.search.{root,node}——未配置即继承 add 侧同键；
 * 可选生长键：growth.prompt、growth.extend.prompt——真实后端生长/延伸必配（生成器构造时读取），
 * mock 后端走 MockGenerator 不读文件。
 */
final class PromptLoader {
    private PromptLoader() { }

    /** 读提示词文件：key 用于报错定位，path 为配置值。 */
    static String read(String key, String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalStateException("提示词文件路径未配置：" + key
                    + "（semquest.properties 中该键应指向 prompts/ 下的文件）");
        }
        Path p = Paths.get(path.trim());
        if (!Files.isReadable(p)) {
            throw new IllegalStateException("提示词文件不可读：" + key + " → " + path.trim()
                    + "（进程工作目录=" + System.getProperty("user.dir") + "）");
        }
        try {
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) {
                throw new IllegalStateException("提示词文件内容为空：" + key + " → " + path.trim());
            }
            return s;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("提示词文件读取失败：" + key + " → " + path.trim()
                    + "：" + e.getMessage());
        }
    }

    /** 可选读：路径未配置返回 null（分野键继承 add 侧）；配置了路径就强制文件可读。 */
    static String readOptional(String key, String path) {
        if (path == null || path.trim().isEmpty()) return null;
        return read(key, path);
    }

    /** semquest.properties → 判定提示词（全部来自 prompt.* 配置的提示词文件，代码零内置文本）。 */
    static SemQuestTree.Prompts of(Config cfg) {
        String noulAdd = read("prompt.noul.add.instructions", cfg.get("prompt.noul.add.instructions", null));
        String choiceRoot = read("prompt.choice.add.root", cfg.get("prompt.choice.add.root", null));
        String choiceNode = read("prompt.choice.add.node", cfg.get("prompt.choice.add.node", null));
        String noulSearch = readOptional("prompt.noul.search.instructions",
                cfg.get("prompt.noul.search.instructions", null));
        if (noulSearch == null) noulSearch = noulAdd;
        String searchRoot = readOptional("prompt.choice.search.root", cfg.get("prompt.choice.search.root", null));
        if (searchRoot == null) searchRoot = choiceRoot;
        String searchNode = readOptional("prompt.choice.search.node", cfg.get("prompt.choice.search.node", null));
        if (searchNode == null) searchNode = choiceNode;
        return new SemQuestTree.Prompts(
                noulAdd, noulSearch,
                read("prompt.noul.search.criterion", cfg.get("prompt.noul.search.criterion", null)),
                read("prompt.noul.add.criterion", cfg.get("prompt.noul.add.criterion", null)),
                read("prompt.noul.search.query", cfg.get("prompt.noul.search.query", null)),
                choiceRoot, choiceNode, searchRoot, searchNode,
                read("prompt.choice.other", cfg.get("prompt.choice.other", null)),
                read("prompt.noul.no.han", cfg.get("prompt.noul.no.han", null)),
                read("prompt.noul.no.latin", cfg.get("prompt.noul.no.latin", null)));
    }

    /** 树级规则（建表默认与热载置换共用）：prompt.rule.{add,search} 配置的提示词文件内容，必配。 */
    static String rule(Config cfg, String key) {
        return read(key, cfg.get(key, null));
    }
}
