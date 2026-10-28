package org.example;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Properties;

/**
 * 全局配置：所有配置集中在一个文件（semquest.properties）——后端选择、门控、
 * schema 路径、超时与三个后端的端点/模型/凭据/手动模型清单。
 *
 * <p>优先级：JVM 系统属性（-Dsemquest.KEY=value）&gt; 配置文件 &gt; 代码内默认。
 * 配置文件路径可用 -Dsemquest.config=... 指定，默认项目根目录 semquest.properties。
 *
 * <p>支持热载：{@link #reload()} 重读配置文件并原子替换内部 Properties（volatile），
 * 服务端 2s 轮询文件 mtime 调用；get() 无锁读，保存文件即生效（除部署硬性数据）。
 */
public final class Config {

    public static final String DEFAULT_FILE = "semquest.properties";

    private final String file;
    private volatile Properties p;
    private volatile boolean loaded;

    private Config(String file, Properties p, boolean loaded) {
        this.file = file;
        this.p = p;
        this.loaded = loaded;
    }

    public static Config load() throws IOException {
        return load(System.getProperty("semquest.config", DEFAULT_FILE));
    }

    public static Config load(String path) throws IOException {
        Properties p = new Properties();
        File f = new File(path);
        boolean loaded = f.isFile();
        if (loaded) {
            FileInputStream in = new FileInputStream(f);
            try {
                p.load(new InputStreamReader(in, Charset.forName("UTF-8")));
            } finally {
                in.close();
            }
        }
        return new Config(path, p, loaded);
    }

    /** 配置文件路径（供日志展示）。 */
    public String file() {
        return file;
    }

    /** 配置文件是否存在（false = 全部走默认值）。 */
    public boolean loaded() {
        return loaded;
    }

    /**
     * 热载：重读配置文件并原子替换内部 Properties。
     * 系统属性优先级不变；读失败（文件被编辑器半写入等）保留旧配置返回 false。
     *
     * @return true = 重读成功（内容可与旧的一致）
     */
    public synchronized boolean reload() {
        File f = new File(file);
        if (!f.isFile()) return false;
        try {
            Properties np = new Properties();
            FileInputStream in = new FileInputStream(f);
            try {
                np.load(new InputStreamReader(in, Charset.forName("UTF-8")));
            } finally {
                in.close();
            }
            this.p = np;
            this.loaded = true;
            return true;
        } catch (Exception e) {
            return false;   // 半写入/IO 抖动：保留旧配置
        }
    }

    public String get(String key, String dft) {
        String v = System.getProperty("semquest." + key);
        if (v == null || v.trim().isEmpty()) v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) return dft;
        return v.trim();
    }

    public int getInt(String key, int dft) {
        try {
            return Integer.parseInt(get(key, String.valueOf(dft)));
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    public double getDouble(String key, double dft) {
        try {
            return Double.parseDouble(get(key, String.valueOf(dft)));
        } catch (NumberFormatException e) {
            return dft;
        }
    }
}
