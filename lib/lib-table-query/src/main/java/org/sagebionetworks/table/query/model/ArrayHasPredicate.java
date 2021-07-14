package org.sagebionetworks.table.query.model;

import java.util.List;

/**
 * Custom "HAS" predicate for searching multi-value columns.
 *
 * <HAS predicate> ::= <row value constructor> [ NOT ] IN <in predicate value> 
 *
 * Examples:
 * columnName HAS ("value1", "value2", "value3")
 *
 * See  https://sagebionetworks.jira.com/wiki/spaces/PLFM/pages/817168468/Multiple+Value+Annotations
 *
 * Related: {@link ArrayFunctionSpecification}
 *
 * NOTE the implemented {@link HasPredicate} interface is not for the "HAS" keyword, but, instead an interface for any predicate
 */
public class ArrayHasPredicate extends SQLElement implements HasPredicate {
	
	private static final String KEYWORD = "HAS";

	ColumnReference columnReferenceLHS;
	Boolean not;
	InPredicateValue inPredicateValue;
	
	public ArrayHasPredicate(ColumnReference columnReferenceLHS, Boolean not, InPredicateValue inPredicateValue) {
		this.columnReferenceLHS = columnReferenceLHS;
		this.not = not;
		this.inPredicateValue = inPredicateValue;
	}
	
	public String getKeyWord() {
		return KEYWORD;
	}

	public Boolean getNot() {
		return not;
	}
	
	public InPredicateValue getInPredicateValue() {
		return inPredicateValue;
	}

	@Override
	public void toSql(StringBuilder builder, ToSqlParameters parameters) {
		columnReferenceLHS.toSql(builder, parameters);
		builder.append(" ");
		if (this.not != null) {
			builder.append("NOT ");
		}
		builder.append(getKeyWord());
		builder.append(" ( ");
		inPredicateValue.toSql(builder, parameters);
		builder.append(" )");
	}

	@Override
	<T extends Element> void addElements(List<T> elements, Class<T> type) {
		checkElement(elements, type, columnReferenceLHS);
		checkElement(elements, type, inPredicateValue);
	}
	
	@Override
	public Iterable<Element> children() {
		return SQLElement.buildChildren(columnReferenceLHS, inPredicateValue);
	}

	@Override
	public ColumnReference getLeftHandSide() {
		return columnReferenceLHS;
	}

	@Override
	public Iterable<UnsignedLiteral> getRightHandSideValues() {
		return inPredicateValue.createIterable(UnsignedLiteral.class);
	}

}
