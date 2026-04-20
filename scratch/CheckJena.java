import org.apache.jena.query.QueryExecution;
import java.lang.reflect.Method;
import java.util.Arrays;

public class CheckJena {
    public static void main(String[] args) {
        try {
            Method[] methods = QueryExecution.class.getMethods();
            for (Method m : methods) {
                if (m.getName().contains("timeout") || m.getName().contains("setTimeout")) {
                    System.out.println(m);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
