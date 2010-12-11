package org.codehaus.jackson.map.deser;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;

import org.codehaus.jackson.*;
import org.codehaus.jackson.map.*;
import org.codehaus.jackson.map.introspect.AnnotatedField;
import org.codehaus.jackson.map.introspect.AnnotatedMethod;
import org.codehaus.jackson.type.JavaType;
import org.codehaus.jackson.util.InternCache;

/**
 * Base class for settable properties of a bean: contains
 * both type and name definitions, and reflection-based set functionality.
 * Concrete sub-classes implement details, so that both field- and
 * setter-backed properties can be handled
 */
public abstract class SettableBeanProperty
{
    /**
     * Logical property object which contains information such
     * as mutator method or field and property nameBeanProperty property
     * 
     * @since 1.7
     */
    protected final BeanProperty _property;
    
    /**
     * Logical name of the property (often but not always derived
     * from the setter method name)
     */
    protected final String _propName;

    /**
     * Base type for property; may be a supertype of actual value.
     */
    protected final JavaType _type;

    /**
     * Deserializer used for handling property value.
     */
    protected JsonDeserializer<Object> _valueDeserializer;

    /**
     * If value will contain type information (to support
     * polymorphic handling), this is the type deserializer
     * used to handle type resolution.
     */
    protected TypeDeserializer _valueTypeDeserializer;
    
    /**
     * Value to be used when 'null' literal is encountered in JSON.
     * For most types simply Java null, but for primitive types must
     * be a non-null value (like Integer.valueOf(0) for int).
     */
    protected NullProvider _nullProvider;

    /**
     * If property represents a managed (forward) reference
     * (see [JACKSON-235]), we will need name of reference for
     * later linking.
     */
    protected String _managedReferenceName;
    
    /*
    /**********************************************************
    /* Life-cycle (construct & configure)
    /**********************************************************
     */

    protected SettableBeanProperty(BeanProperty property, JavaType type, TypeDeserializer typeDeser)
    {
        _property = property;
        /* 09-Jan-2009, tatu: Intern()ing makes sense since Jackson parsed
         *   field names are (usually) interned too, hence lookups will be faster.
         */
        String propName = property.getName();
        // 23-Oct-2009, tatu: should this be disabled wrt [JACKSON-180]?
        if (propName == null || propName.length() == 0) {
            _propName = "";
        } else {
            _propName = InternCache.instance.intern(propName);
        }
        _type = type;
        _valueTypeDeserializer = typeDeser;
    }

    public void setValueDeserializer(JsonDeserializer<Object> deser)
    {
        if (_valueDeserializer != null) { // sanity check
            throw new IllegalStateException("Already had assigned deserializer for property '"+_propName+"' (class "+getDeclaringClass().getName()+")");
        }
        _valueDeserializer = deser;
        Object nvl = _valueDeserializer.getNullValue();
        _nullProvider = (nvl == null) ? null : new NullProvider(_type, nvl);
    }

    public void setManagedReferenceName(String n) {
        _managedReferenceName = n;
    }
    
    protected abstract Class<?> getDeclaringClass();

    /*
    /**********************************************************
    /* Accessors
    /**********************************************************
     */

    /**
     * @since 1.7
     */
    public BeanProperty getProperty() { return _property; }
    
    public String getPropertyName() { return _propName; }
    public JavaType getType() { return _type; }

    public String getManagedReferenceName() { return _managedReferenceName; }
    
    public boolean hasValueDeserializer() { return (_valueDeserializer != null); }

    /**
     * Method to use for accessing index of the property (related to
     * other properties in the same context); currently only applicable
     * to "Creator properties".
     *<p>
     * Base implementation returns -1 to indicate that no index exists
     * for the property.
     */
    public int getCreatorIndex() { return -1; }

    /*
    /**********************************************************
    /* Public API
    /**********************************************************
     */

    /**
     * Method called to deserialize appropriate value, given parser (and
     * context), and set it using appropriate mechanism.
     * Pre-condition is that passed parser must point to the first token
     * that should be consumed to produce the value (the only value for
     * scalars, multiple for Objects and Arrays).
     */
    public abstract void deserializeAndSet(JsonParser jp, DeserializationContext ctxt,
                                           Object instance)
        throws IOException, JsonProcessingException;

    public abstract void set(Object instance, Object value)
        throws IOException;

    /**
     * This method is needed by some specialized bean deserializers,
     * and also called by some {@link #deserializeAndSet} implementations.
     *<p>
     * Pre-condition is that passed parser must point to the first token
     * that should be consumed to produce the value (the only value for
     * scalars, multiple for Objects and Arrays).
     */
    public final Object deserialize(JsonParser jp, DeserializationContext ctxt)
        throws IOException, JsonProcessingException
    {
        JsonToken t = jp.getCurrentToken();
        if (t == JsonToken.VALUE_NULL) {
            return (_nullProvider == null) ? null : _nullProvider.nullValue(ctxt);
        }
        if (_valueTypeDeserializer != null) {
            return _valueDeserializer.deserializeWithType(jp, ctxt, _valueTypeDeserializer);
        }
        return _valueDeserializer.deserialize(jp, ctxt);
    }

    /*
    /**********************************************************
    /* Helper methods
    /**********************************************************
     */

    /**
     * Method that takes in exception of any type, and casts or wraps it
     * to an IOException or its subclass.
     */
    protected void _throwAsIOE(Exception e, Object value)
        throws IOException
    {
        if (e instanceof IllegalArgumentException) {
            String actType = (value == null) ? "[NULL]" : value.getClass().getName();
            StringBuilder msg = new StringBuilder("Problem deserializing property '").append(getPropertyName());
            msg.append("' (expected type: ").append(getType());
            msg.append("; actual type: ").append(actType).append(")");
            String origMsg = e.getMessage();
            if (origMsg != null) {
                msg.append(", problem: ").append(origMsg);
            } else {
                msg.append(" (no error message provided)");
            }
            throw new JsonMappingException(msg.toString(), null, e);
        }
        _throwAsIOE(e);
    }

    protected IOException _throwAsIOE(Exception e)
        throws IOException
    {
        if (e instanceof IOException) {
            throw (IOException) e;
        }
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }
        // let's wrap the innermost problem
        Throwable th = e;
        while (th.getCause() != null) {
            th = th.getCause();
        }
        throw new JsonMappingException(th.getMessage(), null, th);
    }
    
    @Override public String toString() { return "[property '"+_propName+"']"; }

    /*
    /**********************************************************
    /* Impl classes
    /**********************************************************
     */

    /**
     * This concrete sub-class implements property that is set
     * using regular "setter" method.
     */
    public final static class MethodProperty
        extends SettableBeanProperty
    {
        /**
         * Setter method for modifying property value; used for
         * "regular" method-accessible properties.
         */
        protected final Method _setter;

        public MethodProperty(BeanProperty property, AnnotatedMethod method, JavaType type, TypeDeserializer typeDeser)
        {
            super(property, type, typeDeser);
            _setter = method.getAnnotated();
        }

        @Override
        protected Class<?> getDeclaringClass()
        {
            return _setter.getDeclaringClass();
        }

        @Override
        public void deserializeAndSet(JsonParser jp, DeserializationContext ctxt,
                Object instance)
            throws IOException, JsonProcessingException
        {
            set(instance, deserialize(jp, ctxt));
        }

        @Override
        public final void set(Object instance, Object value)
            throws IOException
        {
            try {
                _setter.invoke(instance, value);
            } catch (Exception e) {
                _throwAsIOE(e, value);
            }
        }
    }

    /**
     * This concrete sub-class implements Collection or Map property that is
     * indirectly by getting the property value and directly modifying it.
     */
    public final static class SetterlessProperty
        extends SettableBeanProperty
    {
        /**
         * Get method for accessing property value used to access property
         * (of Collection or Map type) to modify.
         */
        protected final Method _getter;

        public SetterlessProperty(BeanProperty property, AnnotatedMethod method, JavaType type, TypeDeserializer typeDeser)
        {
            super(property, type, typeDeser);
            _getter = method.getAnnotated();
        }

        @Override
        protected Class<?> getDeclaringClass()
        {
            return _getter.getDeclaringClass();
        }
        
        @Override
        public final void deserializeAndSet(JsonParser jp, DeserializationContext ctxt,
                Object instance)
            throws IOException, JsonProcessingException
        {
            JsonToken t = jp.getCurrentToken();
            if (t == JsonToken.VALUE_NULL) {
                /* Hmmh. Is this a problem? We won't be setting anything, so it's
                 * equivalent of empty Collection/Map in this case
                 */
                return;
            }

            // Ok: then, need to fetch Collection/Map to modify:
            Object toModify;
            try {
                toModify = _getter.invoke(instance);
            } catch (Exception e) {
                _throwAsIOE(e);
                return; // never gets here
            }
            /* Note: null won't work, since we can't then inject anything
             * in. At least that's not good in common case. However,
             * theoretically the case where we get JSON null might
             * be compatible. If so, implementation could be changed.
             */
            if (toModify == null) {
                throw new JsonMappingException("Problem deserializing 'setterless' property '"+getPropertyName()+"': get method returned null");
            }
            _valueDeserializer.deserialize(jp, ctxt, toModify);
        }

        @Override
        public final void set(Object instance, Object value)
            throws IOException
        {
            throw new UnsupportedOperationException("Should never call 'set' on setterless property");
        }
    }

    /**
     * This concrete sub-class implements property that is set
     * directly assigning to a Field.
     */
    public final static class FieldProperty
        extends SettableBeanProperty
    {
        /**
         * Actual field to set when deserializing this property.
         */
        protected final Field _field;

        public FieldProperty(BeanProperty property, AnnotatedField field, JavaType type, TypeDeserializer typeDeser)
        {
            super(property, type, typeDeser);
            _field = field.getAnnotated();
        }

        @Override
        protected Class<?> getDeclaringClass()
        {
            return _field.getDeclaringClass();
        }

        @Override
        public void deserializeAndSet(JsonParser jp, DeserializationContext ctxt,
                                      Object instance)
            throws IOException, JsonProcessingException
        {
            set(instance, deserialize(jp, ctxt));
        }

        @Override
        public final void set(Object instance, Object value)
            throws IOException
        {
            try {
                _field.set(instance, value);
            } catch (Exception e) {
                _throwAsIOE(e, value);
            }
        }
    }

    /**
     * This concrete sub-class implements property that is passed
     * via Creator (constructor or static factory method).
     */
    public final static class CreatorProperty
        extends SettableBeanProperty
    {
        final protected Class<?> _declaringClass;

        /**
         * Index of the property
         */
        final protected int _index;

        public CreatorProperty(BeanProperty property, JavaType type, TypeDeserializer typeDeser,
                Class<?> declaringClass, int index)
        {
            super(property, type, typeDeser);
            _declaringClass = declaringClass;
            _index = index;
        }

        /**
         * Method to use for accessing index of the property (related to
         * other properties in the same context); currently only applicable
         * to "Creator properties".
         *<p>
         * Base implementation returns -1 to indicate that no index exists
         * for the property.
         */
        @Override
        public int getCreatorIndex() { return _index; }
        
        @Override
        protected Class<?> getDeclaringClass()
        {
            return _declaringClass;
        }

        @Override
        public void deserializeAndSet(JsonParser jp, DeserializationContext ctxt,
                                      Object instance)
            throws IOException, JsonProcessingException
        {
            set(instance, deserialize(jp, ctxt));
        }

        @Override
        public void set(Object instance, Object value)
            throws IOException
        {
            /* Hmmmh. Should we return quietly (NOP), or error?
             * For now, let's just bail out without fuss.
             */
            //throw new IllegalStateException("Method should never be called on a "+getClass().getName());
        }
    }

    /**
     * Wrapper property that is used to handle managed (forward) properties
     * (see [JACKSON-235] for more information). Basically just need to
     * delegate first to actual forward property, and 
     * 
     * @author tatu
     */
    public final static class ManagedReferenceProperty
        extends SettableBeanProperty
    {
        protected final String _referenceName;
        
        /**
         * Flag that indicates whether property to handle is a container type
         * (array, Collection, Map) or not.
         */
        protected final boolean _isContainer;
        
        protected final SettableBeanProperty _managedProperty;

        protected final SettableBeanProperty _backProperty;
        
        public ManagedReferenceProperty(BeanProperty property, String refName,
                SettableBeanProperty forward,
                SettableBeanProperty backward, boolean isContainer)
        {
            super(property, forward.getType(), forward._valueTypeDeserializer);
            _referenceName = refName;
            _managedProperty = forward;
            _backProperty = backward;
            _isContainer = isContainer;
        }

        @Override
        protected Class<?> getDeclaringClass()
        {
            return _managedProperty.getDeclaringClass();
        }
    
        @Override
        public void deserializeAndSet(JsonParser jp, DeserializationContext ctxt,
                                      Object instance)
            throws IOException, JsonProcessingException
        {
            set(instance, _managedProperty.deserialize(jp, ctxt));
        }
    
        @Override
        public final void set(Object instance, Object value)
            throws IOException
        {
            _managedProperty.set(instance, value);
            /* And then back reference, if (and only if!) we actually have a non-null
             * reference
             */
            if (value != null) {
                if (_isContainer) { // ok, this gets ugly... but has to do for now
                    if (value instanceof Object[]) {
                        for (Object ob : (Object[]) value) {
                            if (ob != null) {
                                _backProperty.set(ob, instance);                            
                            }
                        }
                    } else if (value instanceof Collection<?>) {
                        for (Object ob : (Collection<?>) value) {
                            if (ob != null) {
                                _backProperty.set(ob, instance);                            
                            }
                        }
                    } else if (value instanceof Map<?,?>) {
                        for (Object ob : ((Map<?,?>) value).values()) {
                            if (ob != null) {
                                _backProperty.set(ob, instance);                            
                            }
                        }
                    } else {
                        throw new IllegalStateException("Unsupported container type ("+value.getClass().getName()
                                +") when resolving reference '"+_referenceName+"'");
                    }
                } else {
                    _backProperty.set(value, instance);
                }
            }
        }
    }

    /**
     * To support [JACKSON-420] we need bit more indirection; this is used to produce
     * artificial failure for primitives that don't accept JSON null as value.
     */
    protected final static class NullProvider
    {
        private final Object _nullValue;

        private final boolean _isPrimitive;
        
        private final Class<?> _rawType;
        
        protected NullProvider(JavaType type, Object nullValue)
        {
            _nullValue = nullValue;
            // [JACKSON-420]
            _isPrimitive = type.isPrimitive();
            _rawType = type.getRawClass();
        }

        public Object nullValue(DeserializationContext ctxt) throws JsonProcessingException
        {
            if (_isPrimitive && ctxt.isEnabled(DeserializationConfig.Feature.FAIL_ON_NULL_FOR_PRIMITIVES)) {
                throw ctxt.mappingException("Can not map JSON null into type "+_rawType.getName()
                        +" (set DeserializationConfig.Feature.FAIL_ON_NULL_FOR_PRIMITIVES to 'false' to allow)");
            }
            return _nullValue;
        }
    }
}
