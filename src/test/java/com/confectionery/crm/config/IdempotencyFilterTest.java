package com.confectionery.crm.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyFilterTest {

    private final IdempotencyFilter filter = new IdempotencyFilter();
    private final AtomicInteger executions = new AtomicInteger();

    private MockHttpServletRequest request(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
        if (key != null) request.addHeader(IdempotencyFilter.HEADER, key);
        return request;
    }

    private MockHttpServletResponse run(String key, int status) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(key), response, (req, res) -> {
            int n = executions.incrementAndGet();
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(status);
            res.setContentType("application/json");
            res.getWriter().write("{\"id\":" + n + "}");
        });
        return response;
    }

    @Test
    void repeatWithSameKeyReturnsFirstResponseWithoutExecuting() throws Exception {
        MockHttpServletResponse first = run("k1", 200);
        MockHttpServletResponse second = run("k1", 200);

        assertThat(executions.get()).isEqualTo(1);
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString()).isEqualTo("{\"id\":1}");
        assertThat(second.getHeader("Idempotent-Replay")).isEqualTo("true");
    }

    @Test
    void failedRequestIsExecutedAgain() throws Exception {
        run("k2", 400);
        run("k2", 200);
        assertThat(executions.get()).isEqualTo(2);
    }

    @Test
    void withoutKeyEveryRequestExecutes() throws Exception {
        run(null, 200);
        run(null, 200);
        assertThat(executions.get()).isEqualTo(2);
    }

    @Test
    void concurrentDuplicateWaitsForFirst() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        Thread first = new Thread(() -> {
            try {
                filter.doFilter(request("k3"), firstResponse, (req, res) -> {
                    executions.incrementAndGet();
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    res.getWriter().write("{\"id\":1}");
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        first.start();
        started.await();

        MockHttpServletResponse[] secondResponse = new MockHttpServletResponse[1];
        Thread second = new Thread(() -> {
            try {
                secondResponse[0] = run("k3", 200);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        second.start();
        Thread.sleep(200);
        release.countDown();
        first.join();
        second.join();

        assertThat(executions.get()).isEqualTo(1);
        assertThat(secondResponse[0].getContentAsString()).isEqualTo("{\"id\":1}");
    }
}
