import java.lang.reflect.Field;
import sun.misc.Unsafe;

/** Build-process-only workaround for Windows machines with unusable AF_UNIX sockets. */
public final class LocalSocketAgent {
    public static void premain(String args) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        Class<?> sockets = Class.forName("sun.nio.ch.UnixDomainSockets");
        Field supported = sockets.getDeclaredField("supported");
        unsafe.putBooleanVolatile(unsafe.staticFieldBase(supported), unsafe.staticFieldOffset(supported), false);
    }
}
