package com.zhuri.coding.content.service.ai.agent.workflow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 通用 DAG 编排器（有向无环图 + 层间并行 + 条件门控短路）。
 *
 * <p>把「多智能体 / 多阶段流程」从硬编码的串行或全量并行批次，升级为可表达「依赖关系」与
 * 「条件分流」的图编排：
 * <ul>
 *   <li><b>拓扑分层</b>：依据节点声明的 {@code dependsOn} 前置依赖，把全部节点划分成若干
 *       "波次"（wave）。波次内节点互无依赖，可经外部 {@code Executor} 并行执行；波次之间
 *       严格串行，后沿读取前沿已归档的产物，保证数据依赖可靠。</li>
 *   <li><b>条件门控</b>：节点可携带 {@code gate} 谓词，作用于已归档的 {@link DagResult}；
 *       当 gate 返回 false 时该节点被<b>裁剪</b>（action 不执行、结果记为 SKIPPED），
 *       用于条件分流——例如安全审查判定违规后，直接短路跳过质量 / SEO 等后续高成本阶段。</li>
 *   <li><b>裁剪传播</b>：若某节点存在任一被裁剪的前置依赖，则该节点同步被裁剪（不执行
 *       action），从而形成短路分支；同层其余无关并行分支不受影响。</li>
 * </ul>
 *
 * <p>注意：节点 action 抛异常不会裁剪本节点（记为 FAILED），也不会自动裁剪后续节点——是否
 * 拦截由调用方在 {@code gate} 中依据 FAILED 状态自行决策，语义更可控。
 *
 * <p>本类无任何业务态、纯编排逻辑，便于独立单测；节点标识 K 可为任意稳定类型
 * （本仓库用 {@link StageType}）。
 *
 * @param <K> 节点标识类型
 */
public final class DagExecutor<K> {

    /** DAG 节点描述（不可变；经内部 {@link Builder} 构造）。 */
    public static final class Node<K> {
        final K key;
        final Set<K> dependsOn;
        final Predicate<DagResult<K>> gate;       // null 表示无门控（依赖就绪即执行）
        final Function<DagResult<K>, Object> action; // 执行体；返回值为该节点产物

        Node(K key, Set<K> dependsOn, Predicate<DagResult<K>> gate,
             Function<DagResult<K>, Object> action) {
            this.key = key;
            this.dependsOn = dependsOn == null ? Set.of() : dependsOn;
            this.gate = gate;
            this.action = action;
        }

        /** 构建器入口。 */
        public static <K> Builder<K> node(K key) {
            return new Builder<>(key);
        }
    }

    /** Node 链式构建器，使 DAG 声明贴近自然语言。 */
    public static final class Builder<K> {
        private final K key;
        private final Set<K> dependsOn = new HashSet<>();
        private Predicate<DagResult<K>> gate;
        private Function<DagResult<K>, Object> action;

        Builder(K key) {
            this.key = key;
        }

        /** 声明前置依赖（须先于本节点完成）。 */
        @SafeVarargs
        public final Builder<K> dependsOn(K... deps) {
            for (K d : deps) {
                dependsOn.add(d);
            }
            return this;
        }

        /** 声明条件门控；gate 返回 false 时本节点被裁剪（短路）。 */
        public Builder<K> gate(Predicate<DagResult<K>> gate) {
            this.gate = gate;
            return this;
        }

        /** 声明执行体；返回值为节点产物，异常记为本节点 FAILED。 */
        public Builder<K> then(Function<DagResult<K>, Object> action) {
            this.action = action;
            return this;
        }

        public Node<K> build() {
            return new Node<>(key, dependsOn, gate, action);
        }
    }

    /** 各节点执行结果归档；读取与写入在多线程波次追加快下仍安全。 */
    public static final class DagResult<K> {
        /** 节点终态 */
        public enum Status {
            /** 成功归档产物（valueOf 可取到值） */
            OK,
            /** 被门控或依赖裁剪（action 未执行），即分支短路 */
            SKIPPED,
            /** action 抛异常 */
            FAILED
        }

        private static final class Entry {
            Status status = Status.SKIPPED;
            Object value;
            Throwable error;
        }

        private final Map<K, Entry> entries = new ConcurrentHashMap<>();

        void register(K key) {
            entries.computeIfAbsent(key, k -> new Entry());
        }

        void markOk(K key, Object v) {
            Entry e = entries.computeIfAbsent(key, k -> new Entry());
            e.value = v;
            e.status = Status.OK;
        }

        void markCut(K key) {
            entries.computeIfAbsent(key, k -> new Entry()).status = Status.SKIPPED;
        }

        void markFailed(K key, Throwable t) {
            Entry e = entries.computeIfAbsent(key, k -> new Entry());
            e.error = t;
            e.status = Status.FAILED;
        }

        /** 节点终态；未在我记录内（正常拓扑下不会发生）返回 SKIPPED。 */
        public Status statusOf(K key) {
            Entry e = entries.get(key);
            return e == null ? Status.SKIPPED : e.status;
        }

        /** 仅状态为 OK 时返回节点产物，否则返回 null。 */
        public Object valueOf(K key) {
            Entry e = entries.get(key);
            return (e == null || e.status != Status.OK) ? null : e.value;
        }

        /** 节点是否被裁剪（短路跳过）。 */
        public boolean isCut(K key) {
            return statusOf(key) == Status.SKIPPED;
        }

        /** 节点 action 抛出的异常（仅 FAILED 时非 null）。 */
        public Throwable errorOf(K key) {
            Entry e = entries.get(key);
            return e == null ? null : e.error;
        }
    }

    /**
     * 执行一组 DAG 节点。
     *
     * @param nodes    全部节点（含各自依赖声明）
     * @param executor 波次内节点并行执行所用线程池；实时用 {@code aiAgentToolExecutor}，测试传同步 Executor
     * @param onCut    节点被裁剪（短路跳过）时回调，可用于派发进度/降级事件
     * @return 各节点结果归档
     * @throws IllegalStateException 当 DAG 存在环或依赖指向不存在的节点，导致无法分层时
     */
    public DagResult<K> execute(List<Node<K>> nodes, Executor executor, Consumer<K> onCut) {
        DagResult<K> result = new DagResult<>();
        for (Node<K> n : nodes) {
            result.register(n.key);
        }
        for (List<Node<K>> wave : topoWaves(nodes)) {
            List<CompletableFuture<Void>> futures = new ArrayList<>(wave.size());
            for (Node<K> node : wave) {
                futures.add(CompletableFuture.runAsync(() -> runNode(node, result, onCut), executor));
            }
            awaitWave(futures);
        }
        return result;
    }

    /** 等待单波次全部子任务完成；异常不应泄漏（各节点内已自吞），只做中断复位。 */
    private void awaitWave(List<CompletableFuture<Void>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // runNode 内部已吞掉节点级异常；这里仅兜底，不影响其余节点
        }
    }

    /** 执行单节点：先判断裁剪（依赖被裁 / 门控不满足），否则执行 action。 */
    private void runNode(Node<K> node, DagResult<K> result, Consumer<K> onCut) {
        // 前置依赖被裁剪 → 本节点同步裁剪（分支短路传播）
        for (K dep : node.dependsOn) {
            if (result.isCut(dep)) {
                result.markCut(node.key);
                fireCut(node.key, onCut);
                return;
            }
        }
        // 条件门控不满足 → 本节点裁剪
        if (node.gate != null && !node.gate.test(result)) {
            result.markCut(node.key);
            fireCut(node.key, onCut);
            return;
        }
        if (node.action == null) {
            result.markOk(node.key, null);
            return;
        }
        try {
            result.markOk(node.key, node.action.apply(result));
        } catch (Throwable t) {
            result.markFailed(node.key, t);
        }
    }

    private void fireCut(K key, Consumer<K> onCut) {
        if (onCut != null) {
            try {
                onCut.accept(key);
            } catch (Exception e) {
                // 回调异常不打断编排
            }
        }
    }

    /**
     * 拓扑分层：把节点划分为若干波次——当前波次为「所有前置依赖都已就绪，但尚未派入任何
     * 之前波次」的节点集合；每轮至少推进一个节点，否则判定存在环/缺失依赖。
     */
    private List<List<Node<K>>> topoWaves(List<Node<K>> nodes) {
        Map<K, Node<K>> byKey = new LinkedHashMap<>();
        for (Node<K> n : nodes) {
            byKey.put(n.key, n);
        }
        Set<K> done = new HashSet<>();
        Set<K> pending = new HashSet<>(byKey.keySet());
        List<List<Node<K>>> waves = new ArrayList<>();
        while (!pending.isEmpty()) {
            List<Node<K>> wave = new ArrayList<>();
            boolean progressed = false;
            for (K key : List.copyOf(pending)) {
                Node<K> node = byKey.get(key);
                if (done.containsAll(node.dependsOn)) {
                    wave.add(node);
                    done.add(key);
                    pending.remove(key);
                    progressed = true;
                }
            }
            if (!progressed) {
                throw new IllegalStateException(
                    "DAG 无法拓扑分层（存在环或依赖指向未知节点）: pending=" + pending);
            }
            waves.add(wave);
        }
        return waves;
    }
}