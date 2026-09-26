package com.zifang.z.bot.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.zifang.z.bot.agent.StreamEvent;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 工单 §1.4：流式回推不许退化成一次性整包。
 *
 * <p>判据是"几帧、什么序、每帧什么形状"，不是"最终文本对不对"。
 * 三个增量 ⇒ 三条 {@code agent_message_chunk}，把 publisher 改成攒齐再发一次就红。</p>
 */
public class AcpStreamPublisherTest {

    private static final class Harness {
        final AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        final AcpConnection conn = AcpFakes.connection(recorder);
        final AcpStreamPublisher publisher = new AcpStreamPublisher(conn, "acp-demo");
    }

    @Test
    public void everyFinalDeltaBecomesItsOwnAgentMessageChunk() {
        Harness h = new Harness();
        h.publisher.onEvent(AcpFakes.delta("你"));
        h.publisher.onEvent(AcpFakes.delta("好"));
        h.publisher.onEvent(AcpFakes.delta("，世界"));

        List<String> frames = h.recorder.lines();
        assertEquals("三个增量必须三帧，攒成一帧就是功能缺失", 3, frames.size());
        for (String frame : frames) {
            JsonNode node = parse(frame);
            assertEquals("session/update", node.path("method").asText());
            assertTrue("通知不带 id", node.path("id").isMissingNode());
            assertEquals("acp-demo", node.path("params").path("sessionId").asText());
            assertEquals("agent_message_chunk",
                    node.path("params").path("update").path("sessionUpdate").asText());
            assertEquals("text", node.path("params").path("update").path("content").path("type").asText());
        }
        assertEquals(Arrays.asList("你", "好", "，世界"), texts(frames));
        assertEquals("拼接后与整包等价，但帧必须是分开的", "你好，世界",
                concat(frames));
    }

    @Test
    public void thoughtDeltasGoToTheirOwnSessionUpdateKind() {
        Harness h = new Harness();
        h.publisher.onEvent(AcpFakes.thought("先看看目录"));
        h.publisher.onEvent(AcpFakes.delta("答案是 3"));

        assertEquals(Arrays.asList("agent_thought_chunk", "agent_message_chunk"),
                kinds(h.recorder.lines()));
        assertEquals(1, h.publisher.messageChunks());
    }

    @Test
    public void toolCallAndResultPairUpByIds() {
        Harness h = new Harness();
        h.publisher.onEvent(AcpFakes.toolRequest("exec", "{\"command\":\"ls\"}"));
        h.publisher.onEvent(AcpFakes.toolRequest("read_file", "{\"path\":\"a.txt\"}"));
        h.publisher.onEvent(AcpFakes.toolResult("exec", "a.txt", true));
        h.publisher.onEvent(AcpFakes.toolResult("read_file", "内容", true));

        List<String> frames = h.recorder.lines();
        assertEquals(Arrays.asList("tool_call", "tool_call", "tool_call_update", "tool_call_update"),
                kinds(frames));
        String firstId = parse(frames.get(0)).path("params").path("update").path("toolCallId").asText();
        String secondId = parse(frames.get(1)).path("params").path("update").path("toolCallId").asText();
        String thirdId = parse(frames.get(2)).path("params").path("update").path("toolCallId").asText();
        String fourthId = parse(frames.get(3)).path("params").path("update").path("toolCallId").asText();
        assertEquals("同名工具两次调用要各自配对", firstId, thirdId);
        assertFalse("不能把第二笔的结果配到第一笔上", secondId.equals(thirdId));
        assertEquals(secondId, fourthId);
        JsonNode start = parse(frames.get(0)).path("params").path("update");
        assertEquals("pending", start.path("status").asText());
        assertEquals("ls", start.path("rawInput").path("command").asText());
        JsonNode end = parse(frames.get(2)).path("params").path("update");
        assertEquals("completed", end.path("status").asText());
        assertEquals(0, h.publisher.openToolCallCount());
    }

    @Test
    public void failedToolResultMarksFailedAndCarriesTheError() {
        Harness h = new Harness();
        h.publisher.onEvent(AcpFakes.toolRequest("exec", "{\"command\":\"false\"}"));
        h.publisher.onEvent(AcpFakes.toolResult("exec", "exit 1", false));
        JsonNode update = parse(h.recorder.lines().get(1)).path("params").path("update");
        assertEquals("failed", update.path("status").asText());
        assertEquals("exit 1", update.path("content").get(0).path("text").asText());
    }

    @Test
    public void unpairedToolResultStillEmitsAFrame() {
        Harness h = new Harness();
        h.publisher.onEvent(AcpFakes.toolResult("ghost", "无主的输出", true));
        List<String> frames = h.recorder.lines();
        assertEquals(1, frames.size());
        JsonNode update = parse(frames.get(0)).path("params").path("update");
        assertEquals("tool_call_update", update.path("sessionUpdate").asText());
        assertTrue("没配对也要给出可辨识的 id，否则客户端永远等不到",
                update.path("toolCallId").asText().startsWith("tc-orphan-"));
    }

    @Test
    public void stepStartAndDoneDoNotEmitFramesButAreCounted() {
        Harness h = new Harness();
        h.publisher.onEvent(new StreamEvent.StepStart(1));
        h.publisher.onEvent(new StreamEvent.StepStart(2));
        h.publisher.onEvent(new StreamEvent.Done("整体答案", 2, 11, 7));
        assertEquals("ACP 没有对应 sessionUpdate，不能凭空造帧", 0, h.recorder.lines().size());
        assertEquals(2, h.publisher.stepStarts());
        assertTrue(h.publisher.lastDone() instanceof StreamEvent.Done);
    }

    @Test
    public void steerAndCompactSurfaceAsThoughts() {
        Harness h = new Harness();
        h.publisher.onEvent(new StreamEvent.SteerInjected("换个思路"));
        h.publisher.onEvent(new StreamEvent.Compacted("摘要", 12, 3));
        assertEquals(Arrays.asList("agent_thought_chunk", "agent_thought_chunk"),
                kinds(h.recorder.lines()));
        assertEquals(Arrays.asList("[steer] 换个思路", "[compact] 12->3"), texts(h.recorder.lines()));
    }

    @Test
    public void errorEventEmitsNoFrameAndIsRememberedForTheRpcFailure() {
        Harness h = new Harness();
        RuntimeException boom = new RuntimeException("上游炸了");
        h.publisher.onEvent(new StreamEvent.ErrorEvent(boom));
        assertEquals("错误不许伪装成一条正常 chunk", 0, h.recorder.lines().size());
        assertEquals(boom, h.publisher.lastError());
    }

    @Test
    public void nullEventAndNullTextAreTolerated() {
        Harness h = new Harness();
        h.publisher.onEvent(null);
        h.publisher.onEvent(AcpFakes.delta(null));
        assertEquals(1, h.recorder.lines().size());
        assertEquals("", texts(h.recorder.lines()).get(0));
    }

    @Test
    public void nonJsonToolOutputIsWrappedNotDropped() {
        Harness h = new Harness();
        h.publisher.onEvent(AcpFakes.toolRequest("exec", "not-json"));
        JsonNode update = parse(h.recorder.lines().get(0)).path("params").path("update");
        assertEquals("not-json", update.path("rawInput").path("text").asText());
    }

    /** 单会话里 20 个增量：帧数必须等于 20（防"攒批"这种退化实现悄悄活下来）。 */
    @Test
    public void twentyDeltasProduceTwentyFramesInOrder() {
        Harness h = new Harness();
        List<String> expected = new ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            expected.add("d" + i);
            h.publisher.onEvent(AcpFakes.delta("d" + i));
        }
        assertEquals(20, h.recorder.lines().size());
        assertEquals(expected, texts(h.recorder.lines()));
    }

    private static JsonNode parse(String line) {
        try {
            return JsonRpc.mapper().readTree(line);
        } catch (java.io.IOException e) {
            throw new AssertionError("帧不是合法 JSON: " + line, e);
        }
    }

    private static List<String> kinds(List<String> frames) {
        List<String> out = new ArrayList<String>();
        for (String f : frames) {
            out.add(parse(f).path("params").path("update").path("sessionUpdate").asText());
        }
        return out;
    }

    private static List<String> texts(List<String> frames) {
        List<String> out = new ArrayList<String>();
        for (String f : frames) {
            out.add(parse(f).path("params").path("update").path("content").path("text").asText());
        }
        return out;
    }

    private static String concat(List<String> frames) {
        StringBuilder sb = new StringBuilder();
        for (String t : texts(frames)) {
            sb.append(t);
        }
        return sb.toString();
    }
}
