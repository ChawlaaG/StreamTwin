import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Method;

public class CheckMethods {
    public static void main(String[] args) throws Exception {
        URL url = new File("C:/Users/manis/.gradle/caches/8.13/transforms/3f1e5b9b9abc152f864e4b6b357b4dc5/transformed/library-2.6.7/jars/classes.jar").toURI().toURL();
        URL url2 = new File("C:/Users/manis/.gradle/caches/8.13/transforms/045667d1ea32f94eea28ed6a86b16307/transformed/encoder-2.6.7/jars/classes.jar").toURI().toURL();
        URLClassLoader loader = new URLClassLoader(new URL[]{url, url2});
        Class<?> clazz = loader.loadClass("com.pedro.library.rtmp.RtmpDisplay");
        System.out.println("RtmpDisplay methods:");
        for(Method m : clazz.getMethods()) {
            if(m.getName().toLowerCase().contains("audio") || m.getName().toLowerCase().contains("micro")) {
                System.out.println(m);
            }
        }
    }
}
