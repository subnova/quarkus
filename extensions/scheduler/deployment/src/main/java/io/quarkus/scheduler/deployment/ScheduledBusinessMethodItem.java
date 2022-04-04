package io.quarkus.scheduler.deployment;

import java.util.List;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.MethodInfo;

import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.builder.item.MultiBuildItem;

public final class ScheduledBusinessMethodItem extends MultiBuildItem {

    private final BeanInfo bean;
    private final List<AnnotationInstance> schedules;
    private final MethodInfo method;
    private final boolean nonBlocking;

    public ScheduledBusinessMethodItem(BeanInfo bean, MethodInfo method, List<AnnotationInstance> schedules) {
        this(bean, method, schedules, false);
    }

    public ScheduledBusinessMethodItem(BeanInfo bean, MethodInfo method, List<AnnotationInstance> schedules,
            boolean hasNonBlockingAnnotation) {
        this.bean = bean;
        this.method = method;
        this.schedules = schedules;
        this.nonBlocking = hasNonBlockingAnnotation || SchedulerDotNames.COMPLETION_STAGE.equals(method.returnType().name())
                || SchedulerDotNames.UNI.equals(method.returnType().name()) || KotlinUtil.isSuspendMethod(method);
    }

    /**
     *
     * @return the bean or {@code null} for a static method
     */
    public BeanInfo getBean() {
        return bean;
    }

    public MethodInfo getMethod() {
        return method;
    }

    public List<AnnotationInstance> getSchedules() {
        return schedules;
    }

    public boolean isNonBlocking() {
        return nonBlocking;
    }

}
