import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class PidWhy {
    public static void main(String[] a) throws Exception {
        Process p = new ProcessBuilder("/bin/sleep", "1").start();
        Class<?> k = p.getClass();
        System.out.println("impl_class=" + k.getName() + " modifiers=" + Modifier.toString(k.getModifiers()));
        try {
            Method m = k.getMethod("pid");
            System.out.println("via_impl_class=" + m.invoke(p));
        } catch (Throwable t) {
            System.out.println("via_impl_class_THROWS=" + t.getClass().getName() + ": " + t.getMessage());
        }
        try {
            Method m = Process.class.getMethod("pid");
            System.out.println("via_Process_class=" + m.invoke(p));
        } catch (Throwable t) {
            System.out.println("via_Process_class_THROWS=" + t.getClass().getName() + ": " + t.getMessage());
        }
        for (Class<?> c = k; c != null && Process.class.isAssignableFrom(c); c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("pid");
                f.setAccessible(true);
                System.out.println("field@" + c.getName() + "=" + f.get(p));
            } catch (Throwable t) {
                String msg = String.valueOf(t.getMessage());
                System.out.println("field@" + c.getName() + "_THROWS=" + t.getClass().getSimpleName()
                        + ": " + msg.substring(0, Math.min(120, msg.length())));
            }
        }
        p.destroy();
    }
}
