
import java.nio.channels.*;
import java.nio.file.*;

public class LockProbe {
    public static void main(String[] args) throws Exception {
        FileChannel ch = FileChannel.open(Paths.get(args[0]),
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock = ch.tryLock(0L, Long.MAX_VALUE, false);
        System.out.println(lock == null ? "NULL" : "GRANTED");
        if (lock != null) {
            lock.release();
        }
        ch.close();
    }
}
