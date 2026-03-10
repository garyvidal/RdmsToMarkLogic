package com.nativelogix.rdbms2marklogic.model.project;

import lombok.Data;

import java.util.List;

@Data
public class XmlTableMapping {
    String sourceSchema;
    String sourceTable;
    String xmlName;
    /** "RootElement", "Elements", "InlineElement", or "CUSTOM". */
    String mappingType;
    boolean wrapInParent;
    String wrapperElementName;
    /** InlineElement only: xmlName of the parent element this is nested inside. */
    String parentRef;
    /** CUSTOM only: JavaScript function body. */
    String customFunction;
    /** CUSTOM only: XSD return type, e.g. "xs:string". */
    String xmlType;
    List<XmlColumnMapping> columns;
}
