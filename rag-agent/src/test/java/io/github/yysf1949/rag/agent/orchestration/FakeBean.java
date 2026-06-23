package io.github.yysf1949.rag.agent.orchestration;

/**
 * Test fixture for {@link SpringAiAgentAdapterTest} — top-level public class
 * to satisfy Java's "public class must match filename" rule AND avoid
 * JDK's nested-class access check on reflective Method.invoke.
 *
 * <p>Must live in its own {@code FakeBean.java} file (not inside the test
 * class) because:</p>
 * <ol>
 *   <li>Public top-level class requires filename match</li>
 *   <li>Nested {@code public} methods on a non-public outer class fail
 *       reflective access checks under JDK 9+ (plan's original design)</li>
 * </ol>
 */
public class FakeBean {
    public record In(String q) {}
    public record Out(String a) {}

    public Out run(In i) { return new Out("ok:" + i.q()); }
}