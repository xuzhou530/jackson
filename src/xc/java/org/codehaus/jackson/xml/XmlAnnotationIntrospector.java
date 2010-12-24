package org.codehaus.jackson.xml;

import javax.xml.namespace.QName;

import org.codehaus.jackson.map.introspect.Annotated;

/**
 * Additional extension interface used above and beyond
 * {@link org.codehaus.jackson.map.AnnotationIntrospector} to
 * handle XML-specific configuration.
 * 
 * @since 1.7
 */
public interface XmlAnnotationIntrospector
{
    /**
     * Method that can be called to figure out generic namespace
     * property for an annotated object.
     *
     * @return Null if annotated thing does not define any
     *   namespace information; non-null namespace (which may
     *   be empty String) otherwise
     */
    public String findNamespace(Annotated ann);

    /**
     * Method used to check whether given annotated element
     * (field, method, constructor parameter) has indicator that suggest
     * it be output as an XML attribute or not (as element)
     */
    public Boolean isOutputAsAttribute(Annotated ann);

    /**
     * Method to check if specified property has annotation that indicates
     * that it should be wrapped in an element; and if so, name to use.
     * Note: local name of "" is used to indicate that name should default
     * to using name (local name and namespace) of property itself.
     */
    public QName findWrapperElement(Annotated ann);
}