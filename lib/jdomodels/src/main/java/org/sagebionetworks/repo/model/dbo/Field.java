package org.sagebionetworks.repo.model.dbo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotate a field in a @{@link Table} class to become a database field
 * 
 * @author marcel
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Field {

	/**
	 * the name of the column
	 */
	String name();

	/**
	 * Is this field an etag?
	 */
	boolean etag() default false;

	/**
	 * Is this field a backup id?
	 */
	boolean backupId() default false;

	/**
	 * Is this field a primary key?
	 */
	boolean primary() default false;

	/**
	 * override of default type conversion
	 */
	String type() default "";

	/**
	 * Is nullable?
	 */
	boolean nullable() default true;

	/**
	 * Is default null
	 */
	boolean defaultNull() default false;

	/**
	 * Is varchar(<size>)
	 * 
	 * eg. varchar=256
	 */
	int varchar() default 0;

	/**
	 * Is char(<size>)
	 * 
	 * eg. char=32
	 */
	int fixedchar() default 0;

	/**
	 * Is blob of <blobtype>
	 * 
	 * eg. blob=mediumblob
	 * 
	 * requires extra mapping, if it is not a straight byte[] or String mapping
	 */
	String blob() default "";

	/**
	 * Additional sql, always appended to column definition
	 */
	String sql() default "";
}
