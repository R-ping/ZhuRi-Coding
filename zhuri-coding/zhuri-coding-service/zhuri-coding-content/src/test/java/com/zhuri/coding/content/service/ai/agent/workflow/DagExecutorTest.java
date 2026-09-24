package com.zhuri.coding.content.service.ai.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 通用 DAG 编排器 {@link DagExecutor} 单测。
 *
 * <p>覆盖：按依赖拓扑分层且层间有序、门控裁剪及裁剪向依赖传播、产物经 valueOf/statusOf 归档、
 * action 抛异常记 FAILED 且不自动裁剪依赖节点、环检测抛 IllegalStateException。
 * 全程使用同步 Executor（Runnable::run）保证执行顺序确定可断言。
 */
class DagExecutorTest {

    private final Executor sync = Runnable::run;

    /** 记录执行过的节点 key，供顺序断言。 */
    private final List<String> executed = new ArrayList<>();

    private static final java.util.function.Consumer<String> NOOP_CUT = key -> {
    };

    @Test
    @DisplayName("拓扑分层：依赖节点严格晚于其前置执行，同层可并行")
    void execute_ordersByDependency() {
        List<DagExecutor.Node<String>> nodes = new ArrayList<>();
        nodes.add(DagExecutor.Node.<String>node("A").then(r -> ok("A")).build());
        nodes.add(DagExecutor.Node.<String>node("B").dependsOn("A").then(r -> ok("B")).build());
        nodes.add(DagExecutor.Node.<String>node("C").dependsOn("A").then(r -> ok("C")).build());
        nodes.add(DagExecutor.Node.<String>node("D").dependsOn("A", "C").then(r -> ok("D")).build());

        DagExecutor<String> dag = new DagExecutor<>();
        DagExecutor.DagResult<String> res = dag.execute(nodes, sync, NOOP_CUT);

        assertTrue(executed.indexOf("A") < executed.indexOf("B"), "依赖 A 必须先于 B");
        assertTrue(executed.indexOf("A") < executed.indexOf("C"), "依赖 A 必须先于 C");
        assertTrue(executed.indexOf("C") < executed.indexOf("D"), "依赖 C 必须先于 D");
        assertEquals("D", executed.get(executed.size() - 1));
        assertEquals(DagExecutor.DagResult.Status.OK, res.statusOf("D"));
        assertEquals("ok-D", res.valueOf("D"));
    }

    @Test
    @DisplayName("门控裁剪：gate=false 的节点不执行，并向依赖它的节点传播裁剪，无关分支照常")
    void execute_gateCutPropagatesToDependents() {
        List<DagExecutor.Node<String>> nodes = new ArrayList<>();
        nodes.add(DagExecutor.Node.<String>node("A").then(r -> ok("A")).build());
        // B gate=false → 被裁剪；C 依赖 B → 传播裁剪；D 依赖 A 且 gate=true → 正常执行
        nodes.add(DagExecutor.Node.<String>node("B").dependsOn("A").gate(r -> false).then(r -> ok("B")).build());
        nodes.add(DagExecutor.Node.<String>node("C").dependsOn("B").then(r -> ok("C")).build());
        nodes.add(DagExecutor.Node.<String>node("D").dependsOn("A").then(r -> ok("D")).build());

        List<String> cut = new ArrayList<>();
        DagExecutor<String> dag = new DagExecutor<>();
        DagExecutor.DagResult<String> res = dag.execute(nodes, sync, cut::add);

        assertTrue(res.isCut("B"), "gate=false 的 B 应被裁剪");
        assertTrue(res.isCut("C"), "依赖被裁的 C 应同步裁剪");
        assertTrue(cut.contains("B") && cut.contains("C"), "裁剪回调应通知 B/C：" + cut);
        assertEquals(DagExecutor.DagResult.Status.OK, res.statusOf("D"), "无关分支 D 不受影响");
        // 仅 A、D 真正执行
        assertTrue(executed.containsAll(List.of("A", "D")));
        assertTrue(!executed.contains("B") && !executed.contains("C"));
    }

    @Test
    @DisplayName("产物传递：后沿可经 valueOf 读取前沿产物")
    void execute_valuePropagation() {
        List<DagExecutor.Node<String>> nodes = new ArrayList<>();
        nodes.add(DagExecutor.Node.<String>node("A").then(r -> "base").build());
        nodes.add(DagExecutor.Node.<String>node("B").dependsOn("A").then(r -> r.valueOf("A") + "-derived").build());

        DagExecutor<String> dag = new DagExecutor<>();
        DagExecutor.DagResult<String> res = dag.execute(nodes, sync, NOOP_CUT);

        assertEquals("base-derived", res.valueOf("B"));
        assertEquals("base", res.valueOf("A"));
    }

    @Test
    @DisplayName("action 抛异常：记 FAILED 而非裁剪，依赖节点不因 FAILED 而短路")
    void execute_actionThrowsMarksFailedNotCut() {
        List<DagExecutor.Node<String>> nodes = new ArrayList<>();
        nodes.add(DagExecutor.Node.<String>node("A").then(r -> {
            throw new IllegalStateException("boom");
        }).build());
        nodes.add(DagExecutor.Node.<String>node("B").dependsOn("A").then(r -> ok("B")).build());

        DagExecutor<String> dag = new DagExecutor<>();
        DagExecutor.DagResult<String> res = dag.execute(nodes, sync, NOOP_CUT);

        assertEquals(DagExecutor.DagResult.Status.FAILED, res.statusOf("A"));
        assertNotNull(res.errorOf("A"));
        assertNull(res.valueOf("A"), "FAILED 无产物");
        assertEquals(DagExecutor.DagResult.Status.OK, res.statusOf("B"), "FAILED 不裁剪依赖节点");
    }

    @Test
    @DisplayName("环检测：依赖成环 → 抛 IllegalStateException")
    void execute_cycleThrows() {
        List<DagExecutor.Node<String>> nodes = new ArrayList<>();
        nodes.add(DagExecutor.Node.<String>node("A").dependsOn("B").then(r -> ok("A")).build());
        nodes.add(DagExecutor.Node.<String>node("B").dependsOn("A").then(r -> ok("B")).build());

        DagExecutor<String> dag = new DagExecutor<>();
        assertThrows(IllegalStateException.class, () -> dag.execute(nodes, sync, NOOP_CUT));
    }

    private Object ok(String key) {
        executed.add(key);
        return "ok-" + key;
    }
}