package com.nativelogix.rdbms2marklogic.model.project;

import lombok.Data;

@Data
public class ProjectMapping {
    DocumentModel documentModel;
    JsonDocumentModel jsonDocumentModel;
    /** "XML" (default), "JSON", or "BOTH". */
    String mappingType = "XML";
}
