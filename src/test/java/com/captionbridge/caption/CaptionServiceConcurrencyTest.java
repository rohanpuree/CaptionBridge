package com.captionbridge.caption;

import com.captionbridge.session.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "captionbridge.stt.provider=mock",
        "spring.datasource.url=jdbc:h2:mem:caption-concurrency;DB_CLOSE_DELAY=-1"
})
class CaptionServiceConcurrencyTest {
    @Autowired
    private SessionService sessions;
    @Autowired
    private CaptionService captions;
    @Autowired
    private TransactionTemplate transaction;

    @Test
    @Timeout(30)
    void concurrentWritesAndRollbackLeaveOneGaplessSequence() throws Exception {
        var session = sessions.create("Concurrent captions");
        var id = session.sessionId();
        try {
            transaction.executeWithoutResult(status -> {
                assertThat(captions.appendFinal(id, "Rolled back").getSeq()).isEqualTo(1L);
                status.setRollbackOnly();
            });
            assertThat(captions.partial(id, "Still provisional").seq()).isEqualTo(1L);
            assertThat(captions.readAfter(id, 0, 10)).isEmpty();

            int count = 96;
            var start = new CountDownLatch(1);
            List<Callable<Long>> writes = IntStream.range(0, count).<Callable<Long>>mapToObj(index -> () -> {
                start.await();
                return captions.appendFinal(id, "Caption " + index).getSeq();
            }).toList();

            try (var pool = Executors.newFixedThreadPool(16)) {
                var pending = writes.stream().map(pool::submit).toList();
                start.countDown();
                for (var write : pending) {
                    assertThat(write.get()).isBetween(1L, (long) count);
                }
            }
            var expected = LongStream.rangeClosed(1, count).boxed().toList();
            assertThat(captions.readAfter(id, 0, count)).extracting(CaptionLine::getSeq)
                    .containsExactlyElementsOf(expected);
            assertThat(captions.partial(id, "Next caption").seq()).isEqualTo(count + 1L);
        } finally {
            sessions.delete(id, session.hostToken());
        }
    }
}
