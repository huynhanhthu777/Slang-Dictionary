package dictionary;

import dictionary.service.Benchmark;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Yêu cầu của đề: 100.000 từ, mọi thao tác dưới 1 giây (đo toàn bộ, gồm lưu dữ liệu).
 * Đo 3 lần (mỗi lần thư mục tạm mới) và so trung vị, vì lần đầu còn tính cả thời gian JVM khởi động.
 */
@Tag("performance")
class PerformanceTest {
    private static double median(List<Benchmark.Result> all, ToDoubleFunction<Benchmark.Result> f) {
        double[] v = all.stream().mapToDouble(f).sorted().toArray();
        return v[v.length / 2];
    }

    @Test void hundredThousandEntries_everyOperationUnderOneSecond() throws Exception {
        List<Benchmark.Result> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Benchmark.Result r = Benchmark.runFullTemp(100_000);
            all.add(r);
            r.lines().forEach(l -> System.out.println("        [run " + (all.size()) + "] " + l));
            assertTrue(r.warmReusedIndex(), "warm start must reuse the saved index");
        }
        assertTrue(median(all, Benchmark.Result::slangLookupMicros) < 1000, "slang lookup (microseconds)");
        assertTrue(median(all, Benchmark.Result::definitionWorstMs) < 1000, "definition search worst case");
        assertTrue(median(all, Benchmark.Result::addMs) < 1000, "add incl. save");
        assertTrue(median(all, Benchmark.Result::editMs) < 1000, "edit incl. save");
        assertTrue(median(all, Benchmark.Result::deleteMs) < 1000, "delete incl. save");
        assertTrue(median(all, Benchmark.Result::resetMs) < 1000, "reset incl. save");
        assertTrue(median(all, Benchmark.Result::warmStartMs) < 3000, "warm start (target about 3 s)");
        assertTrue(median(all, Benchmark.Result::coldStartMs) < 10000, "cold start");
        all.sort(Comparator.comparingDouble(Benchmark.Result::worstMutationMs));
    }
}
