package ca.sqlpower.architect;

import java.util.Comparator;
import java.util.Collections;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.DatabaseMetaData;

public class SQLColumn extends SQLObject implements java.io.Serializable {

	protected SQLTable parent;
	protected String columnName;

	/**
	 * Must be a type defined in java.sql.Types.  Move to enum in 1.5
	 * (we hope!).
	 */
	protected int type;

	/**
	 * This is the native name for the column's type in its source
	 * database.  See {@link #type} for system-independant type.
	 */
	protected String sourceDBTypeName;

	protected int scale;
	protected int precision;
	
	/**
	 * This column's nullability type.  One of:
	 * <ul><li>DatabaseMetaData.columnNoNulls - might not allow NULL values
	 *     <li>DatabaseMetaData.columnNullable - definitely allows NULL values
	 *     <li>DatabaseMetaData.columnNullableUnknown - nullability unknown
	 * </ul>
	 */
	protected int nullable;
	protected String remarks;
	protected String defaultValue;
	protected Integer primaryKeySeq;
	protected boolean autoIncrement;

	/**
	 * Constructs a SQLColumn that will be a part of the given SQLTable.
	 *
	 * @param parentTable The table that this column will think it belongs to.
	 * @param colName This column's name.
	 * @param dataType The number that represents this column's type. See java.sql.Types.
	 * @param nativeType The type as it is called in the source database.
	 * @param scale The length of this column.  Size is type-dependant.
	 * @param precision The number of places of precision after the decimal place for numeric types.
	 * @param nullable This column's nullability.  One of:
	 * <ul><li>DatabaseMetaData.columnNoNulls - might not allow NULL values
	 *     <li>DatabaseMetaData.columnNullable - definitely allows NULL values
	 *     <li>DatabaseMetaData.columnNullableUnknown - nullability unknown
	 * </ul>
	 * @param remarks User-defined remarks about this column
	 * @param defaultValue The value this column will have if another value is not specified.
	 * @param primaryKeySeq This column's position in the table's primary key.  Null if it is not in the PK.
	 * @param isAutoIncrement Does this column auto-increment?
	 */
	public SQLColumn(SQLTable parentTable,
					 String colName,
					 int dataType,
					 String nativeType,
					 int scale,
					 int precision,
					 int nullable,
					 String remarks,
					 String defaultValue,
					 Integer primaryKeySeq,
					 boolean isAutoIncrement) {
		this.parent = parentTable;
		this.columnName = colName;
		this.type = dataType;
		this.sourceDBTypeName = nativeType;
		this.scale = scale;
		this.precision = precision;
		this.nullable = nullable;
		this.remarks = remarks;
		this.defaultValue = defaultValue;
		this.primaryKeySeq = primaryKeySeq;
		this.autoIncrement = isAutoIncrement;

		this.children = Collections.EMPTY_LIST;
	}

	public SQLColumn(SQLTable parent, String colName, int type, int scale, int precision) {
		this(parent, colName, type, null, scale, precision, DatabaseMetaData.columnNullable, null, null, null, false);
	}

	public static void addColumnsToTable(SQLTable addTo,
										 String catalog,
										 String schema,
										 String tableName) 
		throws SQLException, DuplicateColumnException, ArchitectException {
		Connection con = addTo.parentDatabase.getConnection();
		ResultSet rs = null;
		try {
			DatabaseMetaData dbmd = con.getMetaData();
			System.out.println("SQLColumn.addColumnsToTable: catalog="+catalog+"; schema="+schema+"; tableName="+tableName);
			rs = dbmd.getColumns(catalog, schema, tableName, "%");
			while (rs.next()) {
				SQLColumn col = new SQLColumn(addTo,
											  rs.getString(4),  // col name
											  rs.getInt(5), // data type (from java.sql.Types)
											  rs.getString(6), // native type name
											  rs.getInt(7), // column size
											  rs.getInt(9), // decimal size
											  rs.getInt(11), // nullable
											  rs.getString(12), // remarks
											  rs.getString(13), // default value
											  null, // primaryKeySeq
											  false // isAutoIncrement
											  );
				System.out.println("Adding column "+col.getColumnName());
				
				if (addTo.getColumnByName(col.getColumnName()) != null) {
					throw new DuplicateColumnException(addTo, col.getColumnName());
				}
				addTo.children.add(col); // don't use addTo.addColumn() (avoids multiple SQLObjectEvents)

				// XXX: need to find out if column is auto-increment
			}
			rs.close();

			rs = dbmd.getPrimaryKeys(catalog, schema, tableName);
			while (rs.next()) {
				SQLColumn col = addTo.getColumnByName(rs.getString(4));
				col.setPrimaryKeySeq(new Integer(rs.getInt(5)));
				addTo.setPrimaryKeyName(rs.getString(6));
			}
			rs.close();
			rs = null;
		} finally {
			if (rs != null) rs.close();
		}
	}

	/**
	 * A comparator for SQLColumns that only pays attention to the
	 * column names.  For example, if <code>column1</code> has
	 * <code>name = "MY_COLUMN"</code> and <code>type =
	 * VARCHAR(20)</code> and <code>column2</code> has <code>name =
	 * "MY_COLUMN"</code> and type <code>VARCHAR(4)</code>,
	 * <code>compare(column1, column2)</code> will return 0.
	 */
	public static class ColumnNameComparator implements Comparator {
		/**
		 * Forwards to {@link #compare(SQLColumn,SQLColumn)}.
		 *
		 * @throws ClassCastException if o1 or o2 is not of class SQLColumn.
		 */
		public int compare(Object o1, Object o2) {
			return compare((SQLColumn) o1, (SQLColumn) o2);
		}

		/**
		 * See class description for behaviour of this method.
		 */
		public int compare(SQLColumn c1, SQLColumn c2) {
			return c1.columnName.compareTo(c2.columnName);
		}
	}

	public String toString() {
		return getShortDisplayName();
	}

	// ------------------------- SQLObject support -------------------------

	public void populate() throws ArchitectException {
		// SQLColumn doesn't have children, so populate does nothing!
		return;
	}

	public boolean isPopulated() {
		return true;
	}

	public String getShortDisplayName() {
		return columnName+": "+sourceDBTypeName+"("+scale+")";
	}

	public boolean allowsChildren() {
		return false;
	}

	public SQLObject getParent()  {
		return this.parent;
	}
	
	// ------------------------- accessors and mutators --------------------------

	/**
	 * Gets the value of name
	 *
	 * @return the value of name
	 */
	public String getColumnName()  {
		return this.columnName;
	}

	/**
	 * Sets the value of name
	 *
	 * @param argName Value to assign to this.name
	 */
	public void setColumnName(String argName) {
		this.columnName = argName;
	}

	/**
	 * Gets the value of type
	 *
	 * @return the value of type
	 */
	public int getType()  {
		return this.type;
	}

	/**
	 * Sets the value of type
	 *
	 * @param argType Value to assign to this.type
	 */
	public void setType(int argType) {
		this.type = argType;
	}

	/**
	 * Gets the value of scale
	 *
	 * @return the value of scale
	 */
	public int getScale()  {
		return this.scale;
	}

	/**
	 * Sets the value of scale
	 *
	 * @param argScale Value to assign to this.scale
	 */
	public void setScale(int argScale) {
		this.scale = argScale;
	}

	/**
	 * Gets the value of precision
	 *
	 * @return the value of precision
	 */
	public int getPrecision()  {
		return this.precision;
	}

	/**
	 * Sets the value of precision
	 *
	 * @param argPrecision Value to assign to this.precision
	 */
	public void setPrecision(int argPrecision) {
		this.precision = argPrecision;
	}

	/**
	 * Figures out this column's nullability
	 *
	 * @return true iff this.nullable == DatabaseMetaData.columnNullable.
	 */
	public boolean isNullable()  {
		return this.nullable == DatabaseMetaData.columnNullable;
	}

	/**
	 * Gets the value of primaryKey
	 *
	 * @return the value of primaryKey
	 */
	public boolean isPrimaryKey()  {
		return this.primaryKeySeq != null;
	}

	public SQLTable getParentTable() {
		return parent;
	}

	/**
	 * Sets the value of parent
	 *
	 * @param argParent Value to assign to this.parent
	 */
	protected void setParent(SQLTable argParent) {
		this.parent = argParent;
	}

	public int getNullable() {
		return nullable;
	}

	/**
	 * Sets the value of nullable
	 *
	 * @param argNullable Value to assign to this.nullable
	 */
	public void setNullable(int argNullable) {
		this.nullable = argNullable;
	}

	/**
	 * Gets the value of remarks
	 *
	 * @return the value of remarks
	 */
	public String getRemarks()  {
		return this.remarks;
	}

	/**
	 * Sets the value of remarks
	 *
	 * @param argRemarks Value to assign to this.remarks
	 */
	public void setRemarks(String argRemarks) {
		this.remarks = argRemarks;
	}

	/**
	 * Gets the value of defaultValue
	 *
	 * @return the value of defaultValue
	 */
	public String getDefaultValue()  {
		return this.defaultValue;
	}

	/**
	 * Sets the value of defaultValue
	 *
	 * @param argDefaultValue Value to assign to this.defaultValue
	 */
	public void setDefaultValue(String argDefaultValue) {
		this.defaultValue = argDefaultValue;
	}

	/**
	 * Gets the value of primaryKeySeq
	 *
	 * @return the value of primaryKeySeq
	 */
	public Integer getPrimaryKeySeq()  {
		return this.primaryKeySeq;
	}

	/**
	 * Sets the value of primaryKeySeq
	 *
	 * @param argPrimaryKeySeq Value to assign to this.primaryKeySeq
	 */
	public void setPrimaryKeySeq(Integer argPrimaryKeySeq) {
		this.primaryKeySeq = argPrimaryKeySeq;
	}

	/**
	 * Gets the value of autoIncrement
	 *
	 * @return the value of autoIncrement
	 */
	public boolean isAutoIncrement()  {
		return this.autoIncrement;
	}

	/**
	 * Sets the value of autoIncrement
	 *
	 * @param argAutoIncrement Value to assign to this.autoIncrement
	 */
	public void setAutoIncrement(boolean argAutoIncrement) {
		this.autoIncrement = argAutoIncrement;
	}

}
