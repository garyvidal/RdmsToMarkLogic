
package com.nativelogix.rdbms2marklogic.service.generate;

import com.nativelogix.rdbms2marklogic.model.project.NamingCase;
import com.nativelogix.rdbms2marklogic.model.project.XmlColumnMapping;
import com.nativelogix.rdbms2marklogic.model.project.XmlTableMapping;
import com.nativelogix.rdbms2marklogic.util.CaseConverter;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Builds a formatted XML string from a root row and its related child rows,
 * driven by the project's {@link XmlTableMapping} definitions.
 *
 * <p>Child data is supplied as {@link MappedRow} instances, each carrying the raw row
 * plus the recursively-queried inline children for that row. This allows
 * {@code InlineElement} mappings to be nested inside the correct parent element at
 * any depth, matching the structure shown in the UI XML Preview.</p>
 */
@Component
public class XmlDocumentBuilder {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * A single queried row together with any inline children that must be
     * nested inside it in the XML output.
     *
     * @param row            column name → JDBC value for this row
     * @param inlineChildren InlineElement mappings keyed to their own MappedRows,
     *                       to be rendered as child XML elements of this row's element
     */
    public record MappedRow(
            Map<String, Object> row,
            Map<XmlTableMapping, List<MappedRow>> inlineChildren) {}

    /**
     * Builds a single XML document string for one root row.
     *
     * @param rootMapping  the root table mapping (mappingType = "RootElement")
     * @param rootRow      column name → value map for the root row
     * @param childData    root-level child mappings → their MappedRows (with inline children attached)
     * @param casing       naming convention to apply to xmlName values (null = use as-is)
     * @return pretty-printed XML string
     */
    public String build(XmlTableMapping rootMapping,
                        Map<String, Object> rootRow,
                        Map<XmlTableMapping, List<MappedRow>> childData,
                        NamingCase casing) throws ParserConfigurationException, TransformerException {

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();

        Element rootEl = doc.createElement(applyCase(rootMapping.getXmlName(), casing));
        doc.appendChild(rootEl);

        applyColumns(doc, rootEl, rootMapping.getColumns(), rootRow);

        if (childData != null) {
            for (Map.Entry<XmlTableMapping, List<MappedRow>> entry : childData.entrySet()) {
                XmlTableMapping childMapping = entry.getKey();
                List<MappedRow> mappedRows = entry.getValue();

                Element parent = rootEl;

                if (childMapping.isWrapInParent() && childMapping.getWrapperElementName() != null && !childMapping.getWrapperElementName().isBlank()) {
                    Element wrapper = doc.createElement(childMapping.getWrapperElementName());
                    rootEl.appendChild(wrapper);
                    parent = wrapper;
                }

                for (MappedRow mr : mappedRows) {
                    Element childEl = doc.createElement(childMapping.getXmlName());
                    applyColumns(doc, childEl, childMapping.getColumns(), mr.row());
                    buildInlineChildren(doc, childEl, mr.inlineChildren());
                    parent.appendChild(childEl);
                }
            }
        }

        return serialize(doc);
    }

    // -------------------------------------------------------------------------

    /**
     * Recursively renders inline children into {@code parentEl}.
     * Each inline mapping produces its own XML element nested inside the parent.
     */
    private void buildInlineChildren(Document doc, Element parentEl,
                                     Map<XmlTableMapping, List<MappedRow>> inlineData) {
        if (inlineData == null || inlineData.isEmpty()) return;

        for (Map.Entry<XmlTableMapping, List<MappedRow>> entry : inlineData.entrySet()) {
            XmlTableMapping mapping = entry.getKey();
            List<MappedRow> rows = entry.getValue();

            for (MappedRow mr : rows) {
                Element inlineEl = doc.createElement(mapping.getXmlName());
                applyColumns(doc, inlineEl, mapping.getColumns(), mr.row());
                buildInlineChildren(doc, inlineEl, mr.inlineChildren());
                parentEl.appendChild(inlineEl);
            }
        }
    }

    private void applyColumns(Document doc, Element parent,
                              List<XmlColumnMapping> columns,
                              Map<String, Object> row) {
        if (columns == null) return;

        for (XmlColumnMapping col : columns) {
            if ("CUSTOM".equals(col.getMappingType())) continue;  // phase 2

            Object rawValue = row.get(col.getSourceColumn());
            if (rawValue == null) continue;  // omit null values

            String value = formatValue(rawValue, col.getXmlType());
            String name  = col.getXmlName();

            if ("ElementAttribute".equals(col.getMappingType())) {
                parent.setAttribute(name, value);
            } else {
                // Default: Element
                Element el = doc.createElement(name);
                el.setTextContent(value);
                parent.appendChild(el);
            }
        }
    }

    /** Format a JDBC value to a string appropriate for the declared XSD type. */
    private String formatValue(Object value, String xmlType) {
        if (xmlType != null) {
            switch (xmlType) {
                case "xs:date" -> {
                    if (value instanceof Date d)      return d.toLocalDate().format(ISO_DATE);
                    if (value instanceof Timestamp ts) return ts.toLocalDateTime().toLocalDate().format(ISO_DATE);
                    if (value instanceof LocalDate ld) return ld.format(ISO_DATE);
                }
                case "xs:dateTime" -> {
                    if (value instanceof Timestamp ts)   return ts.toLocalDateTime().format(ISO_DATETIME);
                    if (value instanceof LocalDateTime l) return l.format(ISO_DATETIME);
                }
                case "xs:decimal", "xs:float", "xs:double" -> {
                    if (value instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
                    if (value instanceof Number n)      return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
                }
                case "xs:boolean" -> {
                    if (value instanceof Boolean b) return b.toString();
                    String s = value.toString().trim();
                    return ("1".equals(s) || "true".equalsIgnoreCase(s)) ? "true" : "false";
                }
            }
        }
        return value.toString();
    }

    /** Apply the project's defaultCasing to an xmlName, if casing is set and the name is snake_case. */
    private String applyCase(String xmlName, NamingCase namingCase) {
        if (xmlName == null || namingCase == null) return xmlName;
        CaseConverter.Case target = switch (namingCase) {
            case CAMEL  -> CaseConverter.Case.CAMEL;
            case PASCAL -> CaseConverter.Case.PASCAL;
            case DASH   -> CaseConverter.Case.DASH;
            default     -> CaseConverter.Case.SNAKE;
        };
        return CaseConverter.convert(xmlName, CaseConverter.Case.SNAKE, target);
    }

    private String serialize(Document doc) throws TransformerException {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}
