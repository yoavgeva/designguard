package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.regex.Pattern;

/**
 * Calls of JDK types {@link JdkEffects} counts as effects that, in some uses, do nothing outside the program: reading
 * the data of a JDBC result or binding a statement a caller ran, and preparing what does nothing yet.
 */
final class JdkPassiveCalls {

    private static final String CONSTRUCTOR = "<init>";
    private static final String THREAD = "java.lang.Thread";
    /** What binds a statement's parameters or reads a called procedure's results, rather than running it. */
    private static final Pattern JDBC_BINDING = Pattern.compile("(set|get)[A-Z].*|clearParameters|registerOutParameter|wasNull");

    private JdkPassiveCalls() {
    }

    /**
     * Whether calling the target reads the rows of a JDBC result, as {@code rows.getString("name")} does, or binds a
     * parameter of a statement, as {@code statement.setString(1, name)} does: on a result or statement a method is
     * given, the caller ran the query, and the method only maps or fills in data, as a row mapper or binder does.
     */
    static boolean readsOrBindsJdbcData(CodeUnitAccessTarget target) {
        JavaClass owner = target.getOwner();
        return owner.isAssignableTo("java.sql.ResultSet")
                || owner.isAssignableTo("java.sql.PreparedStatement") && JDBC_BINDING.matcher(target.getName()).matches();
    }

    /**
     * Whether calling the target only prepares something that does nothing yet: constructs an abstract class, as a
     * subclass's {@code super()} into {@code FileChannel} does, or creates or configures a thread it hasn't started, as
     * {@code new Thread(name)} and {@code setDaemon(true)} do.
     */
    static boolean onlyPrepares(CodeUnitAccessTarget target) {
        boolean constructor = CONSTRUCTOR.equals(target.getName());
        return constructor && target.getOwner().getModifiers().contains(JavaModifier.ABSTRACT)
                || target.getOwner().isAssignableTo(THREAD) && (constructor || target.getName().startsWith("set"));
    }
}
