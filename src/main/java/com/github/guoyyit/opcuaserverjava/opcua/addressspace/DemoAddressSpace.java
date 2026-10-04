package com.github.guoyyit.opcuaserverjava.opcua.addressspace;

import com.github.guoyyit.opcuaserverjava.opcua.core.AccessLevel;
import com.github.guoyyit.opcuaserverjava.opcua.core.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.core.NodeClass;
import com.github.guoyyit.opcuaserverjava.opcua.core.NodeId;
import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;

import java.util.List;

/**
 * 本地设备接入演示地址空间：
 * <pre>
 * Root
 *  └─ Objects (Organizes)
 *      └─ DeviceA (HasChild)
 *          ├─ Temperature (HasProperty, Double, 可读写)
 *          ├─ Pressure    (HasProperty, Double, 只读)
 *          └─ SetPoint    (HasProperty, Integer, 可读写)
 * </pre>
 */
public final class DemoAddressSpace {

    public static final String NS = "urn:opcua-server-java:demo";

    public static final NodeId ROOT = new NodeId(NS, "Root");
    public static final NodeId OBJECTS = new NodeId(NS, "Objects");
    public static final NodeId DEVICE_A = new NodeId(NS, "DeviceA");
    public static final NodeId TEMPERATURE = new NodeId(NS, "DeviceA.Temperature");
    public static final NodeId PRESSURE = new NodeId(NS, "DeviceA.Pressure");
    public static final NodeId SET_POINT = new NodeId(NS, "DeviceA.SetPoint");

    public static final String REF_ORGANIZES = "Organizes";
    public static final String REF_HAS_CHILD = "HasChild";
    public static final String REF_HAS_PROPERTY = "HasProperty";

    private DemoAddressSpace() {
    }

    public static List<NodeId> allNodeIds() {
        return List.of(ROOT, OBJECTS, DEVICE_A, TEMPERATURE, PRESSURE, SET_POINT);
    }

    public static InMemoryAddressSpace create() {
        InMemoryAddressSpace space = new InMemoryAddressSpace();
        long now = System.currentTimeMillis();

        addObject(space, ROOT, "Root");
        addObject(space, OBJECTS, "Objects");
        addObject(space, DEVICE_A, "DeviceA");
        addVariable(space, TEMPERATURE, "Temperature", Double.class,
                AccessLevel.READ_WRITE, 21.5d, now);
        addVariable(space, PRESSURE, "Pressure", Double.class,
                AccessLevel.READ, 101.3d, now);
        addVariable(space, SET_POINT, "SetPoint", Integer.class,
                AccessLevel.READ_WRITE, 25, now);

        space.addReference(new Reference(ROOT, REF_ORGANIZES, true, OBJECTS));
        space.addReference(new Reference(OBJECTS, REF_ORGANIZES, true, DEVICE_A));
        space.addReference(new Reference(DEVICE_A, REF_HAS_PROPERTY, true, TEMPERATURE));
        space.addReference(new Reference(DEVICE_A, REF_HAS_PROPERTY, true, PRESSURE));
        space.addReference(new Reference(DEVICE_A, REF_HAS_PROPERTY, true, SET_POINT));
        return space;
    }

    private static void addObject(InMemoryAddressSpace space, NodeId id, String name) {
        space.addNode(new Node(id, NodeClass.OBJECT, name));
    }

    private static void addVariable(InMemoryAddressSpace space, NodeId id, String name,
                                    Class<?> dataType, AccessLevel accessLevel,
                                    Object initialValue, long now) {
        Node node = new Node(id, NodeClass.VARIABLE, name);
        node.setDataType(dataType);
        node.setAccessLevel(accessLevel);
        node.setDataValue(new DataValue(initialValue, StatusCode.GOOD, now, now));
        space.addNode(node);
    }
}
