package ca.sqlpower.architect.swingui;

import ca.sqlpower.architect.*;
import ca.sqlpower.architect.ddl.*;
import ca.sqlpower.architect.etl.*;
import ca.sqlpower.sql.DBConnectionSpec;

import java.awt.Container;
import java.awt.Point;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.ContainerEvent;
import java.awt.event.ContainerListener;
import java.io.*;
import java.util.*;
import javax.swing.ProgressMonitor;

import org.apache.commons.digester.*;
import org.apache.log4j.Logger;
import org.xml.sax.SAXException;
import org.xml.sax.Attributes;

public class SwingUIProject {
	private static final Logger logger = Logger.getLogger(SwingUIProject.class);

	//  ---------------- persistent properties -------------------
	protected String name;
	protected DBTree sourceDatabases;
	protected PlayPen playPen;
	protected File file;
	protected GenericDDLGenerator ddlGenerator;
	protected boolean savingEntireSource;
	protected PLExport plExport;

	// ------------------ load and save support -------------------

	/**
	 * Tracks whether or not this project has been modified since last saved.
	 */
	protected boolean modified;
	
	/**
	 * Should be set to NULL unless we are currently saving the
	 * project, at which time it's writing to the project file.
	 */
	protected PrintWriter out;

	/**
	 * Used for saving only: this is the current indentation amount in
	 * the XML output file.
	 */
	protected int indent = 0;

	/**
	 * During a LOAD, this map maps String ID codes to SQLObject instances.
	 * During a SAVE, it holds mappings from SQLObject instance to String
	 * ID (the inverse of the LOAD mapping).
	 */
	protected Map objectIdMap;

	/**
	 * During a LOAD, this map maps String ID codes to DBCS instances.
	 * During a SAVE, it holds mappings from DBCS instance to String
	 * ID (the inverse of the LOAD mapping).
	 */
	protected Map dbcsIdMap;

	/**
	 * Shows progress during saves and loads.
	 */
	protected ProgressMonitor pm;

	/**
	 * The last value we sent to the progress monitor.
	 */
	protected int progress = 0;

    private ProjectModificationWatcher projectModificationWatcher;

	/**
	 * Sets up a new project with the given name.
	 */
	public SwingUIProject(String name) throws ArchitectException {
		this.name = name;
		setPlayPen(new PlayPen(new SQLDatabase()));
		this.playPen.getDatabase().getConnectionSpec().setSeqNo(9999);
		List initialDBList = new ArrayList();
		initialDBList.add(playPen.getDatabase());
		this.sourceDatabases = new DBTree(initialDBList);
		ddlGenerator = new GenericDDLGenerator();
		plExport = new PLExport();
	}

	// ------------- READING THE PROJECT FILE ---------------
	public void load(InputStream in) throws IOException, ArchitectException {
		dbcsIdMap = new HashMap();
		objectIdMap = new HashMap();
		
		// use digester to read from file
		try {
			setupDigester().parse(in);
		} catch (SAXException ex) {
			logger.error("SAX Exception in config file parse!", ex);
			throw new ArchitectException("Syntax error in Project file", ex);
		} catch (IOException ex) {
			logger.error("IO Exception in config file parse!", ex);
			throw new ArchitectException("I/O Error", ex);
		} catch (Exception ex) {
			logger.error("General Exception in config file parse!", ex);
			throw new ArchitectException("Unexpected Exception", ex);
		}

		((SQLObject) sourceDatabases.getModel().getRoot()).addChild(0, playPen.getDatabase());
	}

	protected Digester setupDigester() {
		Digester d = new Digester();
		d.setValidating(false);
		d.push(this);

		// project name
		d.addCallMethod("architect-project/project-name", "setName", 0); // argument is element body text

		// source DB connection specs
		DBCSFactory dbcsFactory = new DBCSFactory();
		d.addFactoryCreate("architect-project/project-connection-specs/dbcs", dbcsFactory);
		d.addSetProperties
			("architect-project/project-connection-specs/dbcs",
			 new String[] {"connection-name", "driver-class", "jdbc-url", "user-name",
						   "user-pass", "sequence-number", "single-login"},
			 new String[] {"displayName", "driverClass", "url", "user",
						   "pass", "seqNo", "singleLogin"});
		d.addCallMethod("architect-project/project-connection-specs/dbcs", "setName", 0);
		// these instances get picked out of the dbcsIdMap by the SQLDatabase factory

		// source database hierarchy
		d.addObjectCreate("architect-project/source-databases", LinkedList.class);
		d.addSetNext("architect-project/source-databases", "setSourceDatabaseList");

		SQLDatabaseFactory dbFactory = new SQLDatabaseFactory();
		d.addFactoryCreate("architect-project/source-databases/database", dbFactory);
		d.addSetProperties("architect-project/source-databases/database");
		d.addSetNext("architect-project/source-databases/database", "add");

		d.addObjectCreate("architect-project/source-databases/database/catalog", SQLCatalog.class);
		d.addSetProperties("architect-project/source-databases/database/catalog");
		d.addSetNext("architect-project/source-databases/database/catalog", "addChild");

		d.addObjectCreate("*/schema", SQLSchema.class);
		d.addSetProperties("*/schema");
		d.addSetNext("*/schema", "addChild");

		SQLTableFactory tableFactory = new SQLTableFactory();
		d.addFactoryCreate("*/table", tableFactory);
		d.addSetProperties("*/table");
		d.addSetNext("*/table", "addChild");

		SQLFolderFactory folderFactory = new SQLFolderFactory();
		d.addFactoryCreate("*/folder", folderFactory);
		d.addSetProperties("*/folder");
		d.addSetNext("*/folder", "addChild");

		SQLColumnFactory columnFactory = new SQLColumnFactory();
		d.addFactoryCreate("*/column", columnFactory);
		d.addSetProperties("*/column");
		d.addSetNext("*/column", "addChild");
		
		SQLRelationshipFactory relationshipFactory = new SQLRelationshipFactory();
		d.addFactoryCreate("*/relationship", relationshipFactory);
		d.addSetProperties("*/relationship");
		// the factory adds the relationships to the correct PK and FK tables

		ColumnMappingFactory columnMappingFactory = new ColumnMappingFactory();
		d.addFactoryCreate("*/column-mapping", columnMappingFactory);
		d.addSetProperties("*/column-mapping");
		d.addSetNext("*/column-mapping", "addChild");

		SQLExceptionFactory exceptionFactory = new SQLExceptionFactory();
		d.addFactoryCreate("*/sql-exception", exceptionFactory);
		d.addSetProperties("*/sql-exception");
		d.addSetNext("*/sql-exception", "addChild");

		// target database hierarchy
		d.addFactoryCreate("architect-project/target-database", dbFactory);
		d.addSetProperties("architect-project/target-database");
		d.addSetNext("architect-project/target-database", "setTargetDatabase");

		// the play pen
		TablePaneFactory tablePaneFactory = new TablePaneFactory();
		d.addFactoryCreate("architect-project/play-pen/table-pane", tablePaneFactory);
		// factory will add the tablepanes to the playpen

		PPRelationshipFactory ppRelationshipFactory = new PPRelationshipFactory();
		d.addFactoryCreate("architect-project/play-pen/table-link", ppRelationshipFactory);
		
		DDLGeneratorFactory ddlgFactory = new DDLGeneratorFactory();
		d.addFactoryCreate("architect-project/ddl-generator", ddlgFactory);
		d.addSetProperties("architect-project/ddl-generator");


		FileFactory fileFactory = new FileFactory();
		d.addFactoryCreate("*/file", fileFactory);
		d.addSetNext("*/file", "setFile");

		d.addSetNext("architect-project/ddl-generator", "setDDLGenerator");

		return d;
	}
	
	/**
	 * Creates a DBConnectionSpec object and puts a mapping from its
	 * id (in the attributes) to the new instance into the dbcsIdMap.
	 */
	protected class DBCSFactory extends AbstractObjectCreationFactory {
		public Object createObject(Attributes attributes) {
			DBConnectionSpec dbcs = new DBConnectionSpec();
			String id = attributes.getValue("id");
			if (id != null) {
				dbcsIdMap.put(id, dbcs);
			} else {
				logger.warn("No ID found in dbcs element while loading project!");
			}
			return dbcs;
		}
	}

	/**
	 * Creates a SQLDatabase instance and adds it to the objectIdMap.
	 * Also attaches the DBCS referenced by the dbcsref attribute, if
	 * there is such an attribute.
	 */
	protected class SQLDatabaseFactory extends AbstractObjectCreationFactory {
		public Object createObject(Attributes attributes) {
			SQLDatabase db = new SQLDatabase();

			String id = attributes.getValue("id");
			if (id != null) {
				objectIdMap.put(id, db);
			} else {
				logger.warn("No ID found in database element while loading project!");
			}

			String dbcsid = attributes.getValue("dbcs-ref");
			if (dbcsid != null) {
				db.setConnectionSpec((DBConnectionSpec) dbcsIdMap.get(dbcsid));
			}

			String populated = attributes.getValue("populated");
			if (populated != null && populated.equals("false")) {
				db.setPopulated(false);
			}

			return db;
		}
	}

	/**
	 * Creates a SQLTable instance and adds it to the objectIdMap.
	 */
	protected class SQLTableFactory extends AbstractObjectCreationFactory {
		public Object createObject(Attributes attributes) {
			SQLTable tab = new SQLTable();

			String id = attributes.getValue("id");
			if (id != null) {
				objectIdMap.put(id, tab);
			} else {
				logger.warn("No ID found in table element while loading project!");
			}

			String populated = attributes.getValue("populated");
			if (populated != null && populated.equals("false")) {
				tab.initFolders(false);
			}

			return tab;
		}
	}

	/** 
	 * Creates a SQLFolder instance which is marked as populated.
	 */
	protected class SQLFolderFactory extends AbstractObjectCreationFactory {
		public Object createObject(Attributes attributes) {
			int type = -1;
			String typeStr = attributes.getValue("type");
			if (typeStr == null) {
				// backward compatibility: derive type from name
				String name = attributes.getValue("name");
				if (name.equals("Columns")) type = SQLTable.Folder.COLUMNS;
				else if (name.equals("Imported Keys")) type = SQLTable.Folder.IMPORTED_KEYS;
				else if (name.equals("Exported Keys")) type = SQLTable.Folder.EXPORTED_KEYS;
				else throw new IllegalStateException("Could not determine folder type from name");
			} else {
				try {
					type = Integer.parseInt(typeStr);
				} catch (NumberFormatException ex) {
					throw new IllegalStateException("Could not parse folder type id \""
													+typeStr+"\"");
				}
			}
			return new SQLTable.Folder(type, true);
		}
	}

	/**
	 * Creates a SQLColumn instance and adds it to the
	 * objectIdMap. Also dereferences the source-column-ref attribute
	 * if present.
	 */
	protected class SQLColumnFactory extends AbstractObjectCreationFactory {
		public Object createObject(Attributes attributes) {
			SQLColumn col = new SQLColumn();

			String id = attributes.getValue("id");
			if (id != null) {
				objectIdMap.put(id, col);
			} else {
				logger.warn("No ID found in column element while loading project!");
			}

			String sourceId = attributes.getValue("source-column-ref");
			if (sourceId != null) {
				col.setSourceColumn((SQLColumn) objectIdMap.get(sourceId));
			}

			return col;
		}
	}

	/**
	 * Creates a SQLException instance and adds it to the
	 * objectIdMap.
	 */
	protected class SQLExceptionFactory extends AbstractObjectCreationFactory {
		public Object createObject(Attributes attributes) {
			SQLExceptionNode exc = new SQLExceptionNode(null, null);

			String id = attributes.getValue("id");
			if (id != null) {
				objectIdMap.put(id, exc);
			} else {
				logger.warn("No ID found in exception element while loading project!");
			}

			exc.setMessage(attributes.getValue("message"));

			return exc;
		}
	}

	/**
	 * Creates a SQLRelationship instance and adds it to the
	 * objectIdMap.  Also dereferences the fk-table-ref and
	 * pk-table-ref attributes if present.
	 */
	protected class SQLRelationshipFactory extends AbstractObjectCreationFactory {
		public Object createObject(Attributes attributes) {
			SQLRelationship rel = new SQLRelationship();

			String id = attributes.getValue("id");
			if (id != null) {
				objectIdMap.put(id, rel);
			} else {
				logger.warn("No ID found in relationship element while loading project!");
			}

			String fkTableId = attributes.getValue("fk-table-ref");
			if (fkTableId != null) {
				SQLTable fkTable = (SQLTable) objectIdMap.get(fkTableId);
				rel.setFkTable(fkTable);
				fkTable.addImportedKey(rel);
			}

			String pkTableId = attributes.getValue("pk-table-ref");
			if (pkTableId != null) {
				SQLTable pkTable = (SQLTable) objectIdMap.get(pkTableId);
				rel.setPkTable(pkTable);
				pkTable.addExportedKey(rel);
			}

			return rel;
		}
	}

	/**
	 * Creates a ColumnMapping instance and adds it to the
	 * objectIdMap.  Also dereferences the fk-column-ref and
	 * pk-column-ref attributes if present.
	 */
	protected class ColumnMappingFactory extends AbstractObjectCreationFactory {
		public Object createObject(Attributes attributes) {
			SQLRelationship.ColumnMapping cmap = new SQLRelationship.ColumnMapping();

			String id = attributes.getValue("id");
			if (id != null) {
				objectIdMap.put(id, cmap);
			} else {
				logger.warn("No ID found in column-mapping element while loading project!");
			}

			String fkColumnId = attributes.getValue("fk-column-ref");
			if (fkColumnId != null) {
				cmap.setFkColumn((SQLColumn) objectIdMap.get(fkColumnId));
			}

			String pkColumnId = attributes.getValue("pk-column-ref");
			if (pkColumnId != null) {
				cmap.setPkColumn((SQLColumn) objectIdMap.get(pkColumnId));
			}

			return cmap;
		}
	}

	protected class TablePaneFactory extends AbstractObjectCreationFactory {
		public Object createObject(Attributes attributes) {
			int x = Integer.parseInt(attributes.getValue("x"));
			int y = Integer.parseInt(attributes.getValue("y"));
			SQLTable tab = (SQLTable) objectIdMap.get(attributes.getValue("table-ref"));
			TablePane tp = new TablePane(tab, playPen);
			playPen.add(tp, new Point(x, y));
			return tp;
		}
	}

	protected class PPRelationshipFactory extends AbstractObjectCreationFactory {
		public Object createObject(Attributes attributes) {
			Relationship r = null;
			try {
				SQLRelationship rel =
					(SQLRelationship) objectIdMap.get(attributes.getValue("relationship-ref"));
				r = new Relationship(playPen, rel);
				playPen.add(r);

				int pkx = Integer.parseInt(attributes.getValue("pk-x"));
				int pky = Integer.parseInt(attributes.getValue("pk-y"));
				int fkx = Integer.parseInt(attributes.getValue("fk-x"));
				int fky = Integer.parseInt(attributes.getValue("fk-y"));
				r.setPkConnectionPoint(new Point(pkx, pky));
				r.setFkConnectionPoint(new Point(fkx, fky));
			} catch (ArchitectException e) {
				logger.error("Couldn't create relationship component", e);
			} catch (NumberFormatException e) {
				logger.warn("Didn't set connection points because of integer parse error");
			} catch (NullPointerException e) {
				logger.debug("No pk/fk connection points specified in save file;"
							 +" not setting custom connection points");
			}
			return r;
		}
	}

	protected class DDLGeneratorFactory extends AbstractObjectCreationFactory {
		public Object createObject(Attributes attributes) {
			try {
				GenericDDLGenerator ddlg = 
					(GenericDDLGenerator) Class.forName(attributes.getValue("type")).newInstance();
				ddlg.setTargetCatalog(attributes.getValue("target-catalog"));
				ddlg.setTargetSchema(attributes.getValue("target-schema"));
				return ddlg;
			} catch (Exception e) {
				logger.debug("Couldn't create DDL Generator instance. Returning generic instance.", e);
				return new GenericDDLGenerator();
			}
		}
	}

	protected class FileFactory extends AbstractObjectCreationFactory {
		public Object createObject(Attributes attributes) {
			return new File(attributes.getValue("path"));
		}
	}

	// ------------- WRITING THE PROJECT FILE ---------------
	
	/**
	 * Saves this project by writing an XML description of it to disk.  The
	 * location of the file is determined by this project's <code>file</code> property.
	 * 
	 * @param pm An optional progress monitor which will be initialised then updated 
	 * periodically during the save operation.  If you use a progress monitor, don't
	 * invoke this method on the AWT event dispatch thread!
	 */
	public void save(ProgressMonitor pm) throws IOException, ArchitectException {
		out = new PrintWriter(new BufferedWriter(new FileWriter(file)));
		objectIdMap = new HashMap();
		dbcsIdMap = new HashMap();
		indent = 0;
		progress = 0;
		this.pm = pm;
		if (pm != null) {
		    pm.setMinimum(0);
		    int pmMax = countSourceTables((SQLObject) sourceDatabases.getModel().getRoot())
		    				+ playPen.getPPComponentCount() * 2;
		    logger.debug("Setting progress monitor maximum to "+pmMax);
		    pm.setMaximum(pmMax);
		    pm.setProgress(progress);
		    pm.setMillisToDecideToPopup(500);
		}
		try {
			println("<?xml version=\"1.0\"?>");
			println("<architect-project version=\"0.1\">");
			indent++;
			println("<project-name>"+name+"</project-name>");
			saveDBCS();
			saveSourceDatabases();
			saveTargetDatabase();
			saveDDLGenerator();
			savePlayPen();
			indent--;
			println("</architect-project>");
			setModified(false);
		} finally {
			if (out != null) out.close();
			out = null;
			if (pm != null) pm.close();
			pm = null;
		}
	}

	protected int countSourceTables(SQLObject o) throws ArchitectException {
		if (o instanceof SQLTable) {
			return 1;
		} else if (o == playPen.getDatabase()) {
			return 0;
		} else if ( (!o.allowsChildren()) || !(o.isPopulated()) || o.getChildren() == null) {
		    return 0;
		} else {
			int myCount = 0;
			Iterator it = o.getChildren().iterator();
			while (it.hasNext()) {
				myCount += countSourceTables((SQLObject) it.next());
			}
			return myCount;
		}
	}

	protected void saveDBCS() throws IOException, ArchitectException {
		println("<project-connection-specs>");
		indent++;
		int dbcsNum = 0;
		SQLObject dbTreeRoot = (SQLObject) sourceDatabases.getModel().getRoot();
		Iterator it = dbTreeRoot.getChildren().iterator();
		while (it.hasNext()) {
			SQLObject o = (SQLObject) it.next();
			DBConnectionSpec dbcs = ((SQLDatabase) o).getConnectionSpec();
			if (dbcs != null) {
				String id = (String) dbcsIdMap.get(dbcs);
				if (id == null) {
					id = "DBCS"+dbcsNum;
					dbcsIdMap.put(dbcs, id);
				}
				print("<dbcs");
				niprint(" id=\""+id+"\"");
				niprint(" connection-name=\""+dbcs.getName()+"\"");
				niprint(" driver-class=\""+dbcs.getDriverClass()+"\"");
				niprint(" jdbc-url=\""+dbcs.getUrl()+"\"");
				niprint(" user-name=\""+dbcs.getUser()+"\"");
				niprint(" user-pass=\""+dbcs.getPass()+"\"");
				niprint(" sequence-number=\""+dbcs.getSeqNo()+"\"");
				niprint(" single-login=\""+dbcs.isSingleLogin()+"\"");
				niprint(">");
				niprint(dbcs.getDisplayName());
				niprintln("</dbcs>");
				dbcsNum++;
			}
			dbcsNum++;
		}
		indent--;
		println("</project-connection-specs>");
	}

	protected void saveDDLGenerator() throws IOException {
		print("<ddl-generator"
			  +" type=\""+ddlGenerator.getClass().getName()+"\""
			  +" allow-connection=\""+ddlGenerator.getAllowConnection()+"\"");
		if (ddlGenerator.getTargetCatalog() != null) {
			niprint(" target-catalog=\""+ddlGenerator.getTargetCatalog()+"\"");
		}
		if (ddlGenerator.getTargetSchema() != null) {
			niprint(" target-schema=\""+ddlGenerator.getTargetSchema()+"\"");
		}
		niprint(">");
		indent++;
		if (ddlGenerator.getFile() != null) {
			println("<file path=\""+ddlGenerator.getFile().getPath()+"\" />");
		}
		indent--;
		println("</ddl-generator>");
	}

	/**
	 * Creates a &lt;source-databases&gt; element which contains zero
	 * or more &lt;database&gt; elements.
	 */
	protected void saveSourceDatabases() throws IOException, ArchitectException {
		println("<source-databases>");
		indent++;
		SQLObject dbTreeRoot = (SQLObject) sourceDatabases.getModel().getRoot();
		Iterator it = dbTreeRoot.getChildren().iterator();
		while (it.hasNext()) {
			SQLObject o = (SQLObject) it.next();
			if (o != playPen.getDatabase()) {
				saveSQLObject(o);
			}
		}
		indent--;
		println("</source-databases>");
	}
	
	/**
	 * Recursively walks through the children of db, writing to the
	 * output file all SQLRelationship objects encountered.
	 */
	protected void saveRelationships(SQLDatabase db) throws ArchitectException, IOException {
		println("<relationships>");
		indent++;
		Iterator it = db.getChildren().iterator();
		while (it.hasNext()) {
			saveRelationshipsRecurse((SQLObject) it.next());
		}
		indent--;
		println("</relationships>");
	}

	/**
	 * The recursive subroutine of saveRelationships.
	 */
	protected void saveRelationshipsRecurse(SQLObject o) throws ArchitectException, IOException {
		if ( (!savingEntireSource) && (!o.isPopulated()) ) {
			return;
		} else if (o instanceof SQLRelationship) {
			saveSQLObject(o);
		} else if (o.allowsChildren()) {
			Iterator it = o.getChildren().iterator();
			while (it.hasNext()) {
				saveRelationshipsRecurse((SQLObject) it.next());
			}
		}
	}

	protected void saveTargetDatabase() throws IOException, ArchitectException {
		SQLDatabase db = (SQLDatabase) playPen.getDatabase();
		println("<target-database dbcs-ref=\""+dbcsIdMap.get(db.getConnectionSpec())+"\">");
		indent++;
		Iterator it = db.getChildren().iterator();
		while (it.hasNext()) {
			saveSQLObject((SQLObject) it.next());
		}
		saveRelationships(db);
		indent--;
		println("</target-database>");
	}
	
	protected void savePlayPen() throws IOException, ArchitectException {
		println("<play-pen>");
		indent++;
		Iterator it = playPen.getTablePanes().iterator();
		while (it.hasNext()) {
			TablePane tp = (TablePane) it.next();
			Point p = tp.getLocation();
			println("<table-pane table-ref=\""+objectIdMap.get(tp.getModel())+"\""
					+" x=\""+p.x+"\" y=\""+p.y+"\" />");
			if (pm != null) {
			    pm.setProgress(++progress);
			    pm.setNote(tp.getModel().getShortDisplayName());
			}
		}

		it = playPen.getRelationships().iterator();
		while (it.hasNext()) {
			Relationship r = (Relationship) it.next();
			println("<table-link relationship-ref=\""+objectIdMap.get(r.getModel())+"\""
					+" pk-x=\""+r.getPkConnectionPoint().x+"\""
					+" pk-y=\""+r.getPkConnectionPoint().y+"\""
					+" fk-x=\""+r.getFkConnectionPoint().x+"\""
					+" fk-y=\""+r.getFkConnectionPoint().y+"\" />");
		}
		indent--;
		println("</play-pen>");
	}

	/**
	 * Creates an XML element describing the given SQLObject and
	 * writes it to the <code>out</code> PrintWriter.
	 *
	 * <p>Design notes: Attribute names that are straight property
	 * contents (such as name or defaultValue) are chosen so that
	 * automatic JavaBeans population of object properties is
	 * possible.  For the same reasons, attributes that need
	 * non-automatic population (such as reference properties like
	 * pkColumn) are named to purposely disable automatic JavaBeans
	 * property setting.  In the pkColumn example, the XML attribute
	 * name would be pk-column-ref.  Special code in the load routine
	 * is responsible for deferencing the attribute and setting the
	 * property manually.
	 */
	protected void saveSQLObject(SQLObject o) throws IOException, ArchitectException {
		String id = (String) objectIdMap.get(o);
		if (id != null) {
			println("<reference ref-id=\""+id+"\" />");
			return;
		}

		String type;
		Map propNames = new TreeMap();
		
		if (o instanceof SQLDatabase) {
			id = "DB"+objectIdMap.size();
			type = "database";
			propNames.put("dbcs-ref", dbcsIdMap.get(((SQLDatabase) o).getConnectionSpec()));
		} else if (o instanceof SQLCatalog) {
			id = "CAT"+objectIdMap.size();
			type = "catalog";
			propNames.put("catalogName", ((SQLCatalog) o).getCatalogName());
			propNames.put("nativeTerm", ((SQLCatalog) o).getNativeTerm());
		} else if (o instanceof SQLSchema) {
			id = "SCH"+objectIdMap.size();
			type = "schema";
			propNames.put("schemaName", ((SQLSchema) o).getSchemaName());
			propNames.put("nativeTerm", ((SQLSchema) o).getNativeTerm());
		} else if (o instanceof SQLTable) {
			id = "TAB"+objectIdMap.size();
			type = "table";
			propNames.put("tableName", ((SQLTable) o).getTableName());
			propNames.put("remarks", ((SQLTable) o).getRemarks());
			propNames.put("objectType", ((SQLTable) o).getObjectType());
			propNames.put("primaryKeyName", ((SQLTable) o).getPrimaryKeyName());
			if (pm != null) {
			    pm.setProgress(++progress);
			    pm.setNote(o.getShortDisplayName());
			}
		} else if (o instanceof SQLTable.Folder) {
			id = "FOL"+objectIdMap.size();
			type = "folder";
			propNames.put("name", ((SQLTable.Folder) o).getName());
			propNames.put("type", new Integer(((SQLTable.Folder) o).getType()));
		} else if (o instanceof SQLColumn) {
			id = "COL"+objectIdMap.size();
			type = "column";
			SQLColumn sourceCol = ((SQLColumn) o).getSourceColumn();
			if (sourceCol != null) {
				propNames.put("source-column-ref", objectIdMap.get(sourceCol));
			}
			propNames.put("columnName", ((SQLColumn) o).getColumnName());
			propNames.put("type", new Integer(((SQLColumn) o).getType()));
			propNames.put("sourceDBTypeName", ((SQLColumn) o).getSourceDBTypeName());
			propNames.put("scale", new Integer(((SQLColumn) o).getScale()));
			propNames.put("precision", new Integer(((SQLColumn) o).getPrecision()));
			propNames.put("nullable", new Integer(((SQLColumn) o).getNullable()));
			propNames.put("remarks", ((SQLColumn) o).getRemarks());
			propNames.put("defaultValue", ((SQLColumn) o).getDefaultValue());
			propNames.put("primaryKeySeq", ((SQLColumn) o).getPrimaryKeySeq());
			propNames.put("autoIncrement", new Boolean(((SQLColumn) o).isAutoIncrement()));
		} else if (o instanceof SQLRelationship) {
			id = "REL"+objectIdMap.size();
			type = "relationship";
			propNames.put("pk-table-ref", objectIdMap.get(((SQLRelationship) o).getPkTable()));
			propNames.put("fk-table-ref", objectIdMap.get(((SQLRelationship) o).getFkTable()));
			propNames.put("updateRule", new Integer(((SQLRelationship) o).getUpdateRule()));
			propNames.put("deleteRule", new Integer(((SQLRelationship) o).getDeleteRule()));
			propNames.put("deferrability", new Integer(((SQLRelationship) o).getDeferrability()));
			propNames.put("pkCardinality", new Integer(((SQLRelationship) o).getPkCardinality()));
			propNames.put("fkCardinality", new Integer(((SQLRelationship) o).getFkCardinality()));
			propNames.put("identifying", new Boolean(((SQLRelationship) o).isIdentifying()));
			propNames.put("name", ((SQLRelationship) o).getName());
		} else if (o instanceof SQLRelationship.ColumnMapping) {
			id = "CMP"+objectIdMap.size();
			type = "column-mapping";
			propNames.put("pk-column-ref", objectIdMap.get(((SQLRelationship.ColumnMapping) o).getPkColumn()));
			propNames.put("fk-column-ref", objectIdMap.get(((SQLRelationship.ColumnMapping) o).getFkColumn()));
		} else if (o instanceof SQLExceptionNode) {
		    id = "EXC"+objectIdMap.size();
		    type = "sql-exception";
		    propNames.put("message", ((SQLExceptionNode) o).getMessage());
		} else {
			throw new UnsupportedOperationException("Woops, the SQLObject type "
													+o.getClass().getName()+" is not supported!");
		}
	
		objectIdMap.put(o, id);
		
		boolean skipChildren = false;
		
		//print("<"+type+" hashCode=\""+o.hashCode()+"\" id=\""+id+"\" ");  // use this for debugging duplicate object problems
		print("<"+type+" id=\""+id+"\" ");

		if (o.allowsChildren() && o.isPopulated() && o.getChildCount() == 1 && o.getChild(0) instanceof SQLExceptionNode) {
		    // if the only child is an exception node, just save the parent as non-populated
		    niprint("populated=\"false\" ");
		    skipChildren = true;
		} else if ( (!savingEntireSource) && (!o.isPopulated()) ) {
			niprint("populated=\"false\" ");
		} else {
		    niprint("populated=\"true\" ");
		}

		Iterator props = propNames.keySet().iterator();
		while (props.hasNext()) {
			Object key = props.next();
			Object value = propNames.get(key);
			if (value != null) {
				niprint(key+"=\""+value+"\" ");
			}
		}
		if ( (!skipChildren) && o.allowsChildren() && (savingEntireSource || o.isPopulated()) ) {
			niprintln(">");
			Iterator children = o.getChildren().iterator();
			indent++;
			while (children.hasNext()) {
				SQLObject child = (SQLObject) children.next();
				if ( ! (child instanceof SQLRelationship)) {
					saveSQLObject(child);
				}
			}
			if (o instanceof SQLDatabase) {
				saveRelationships((SQLDatabase) o);
			}
			indent--;
			println("</"+type+">");
		} else {
			niprintln("/>");
		}
	}


	// ------------------- accessors and mutators ---------------------
	
	/**
	 * Gets the value of name
	 *
	 * @return the value of name
	 */
	public String getName()  {
		return this.name;
	}

	/**
	 * Sets the value of name
	 *
	 * @param argName Value to assign to this.name
	 */
	public void setName(String argName) {
		this.name = argName;
	}

	/**
	 * Gets the value of sourceDatabases
	 *
	 * @return the value of sourceDatabases
	 */
	public DBTree getSourceDatabases()  {
		return this.sourceDatabases;
	}

	/**
	 * Sets the value of sourceDatabases
	 *
	 * @param argSourceDatabases Value to assign to this.sourceDatabases
	 */
	public void setSourceDatabases(DBTree argSourceDatabases) {
		this.sourceDatabases = argSourceDatabases;
	}

	public void setSourceDatabaseList(List databases) throws ArchitectException {
		this.sourceDatabases.setModel(new DBTreeModel(databases));
	}

 	/**
	 * Gets the target database in the playPen.
	 */
	public SQLDatabase getTargetDatabase()  {
		return playPen.getDatabase();
	}

	/**
	 * Sets the value of target database in the PlayPen.
	 */
	public void setTargetDatabase(SQLDatabase db)  {
		playPen.setDatabase(db);
	}

	/**
	 * Gets the value of file
	 *
	 * @return the value of file
	 */
	public File getFile()  {
		return this.file;
	}

	/**
	 * Sets the value of file
	 *
	 * @param argFile Value to assign to this.file
	 */
	public void setFile(File argFile) {
		this.file = argFile;
	}

	/**
	 * Gets the value of playPen
	 *
	 * @return the value of playPen
	 */
	public PlayPen getPlayPen()  {
		return this.playPen;
	}

	/**
	 * Sets the value of playPen
	 *
	 * @param argPlayPen Value to assign to this.playPen
	 */
	public void setPlayPen(PlayPen argPlayPen) {
		this.playPen = argPlayPen;
		SwingUserSettings sprefs = ArchitectFrame.getMainInstance().sprefs;
		if (sprefs != null) {
		    playPen.setRenderingAntialiased(sprefs.getBoolean(SwingUserSettings.PLAYPEN_RENDER_ANTIALIASED, false));
		}
		projectModificationWatcher = new ProjectModificationWatcher(playPen);
	}

	public GenericDDLGenerator getDDLGenerator() {
		return ddlGenerator;
	}

	public void setDDLGenerator(GenericDDLGenerator generator) {
		ddlGenerator = generator;
	}

	/**
	 * See {@link #savingEntireSource}.
	 *
	 * @return the value of savingEntireSource
	 */
	public boolean isSavingEntireSource()  {
		return this.savingEntireSource;
	}

	/**
	 * See {@link #savingEntireSource}.
	 *
	 * @param argSavingEntireSource Value to assign to this.savingEntireSource
	 */
	public void setSavingEntireSource(boolean argSavingEntireSource) {
		this.savingEntireSource = argSavingEntireSource;
	}

	public PLExport getPLExport() {
		return plExport;
	}

	public void setPLExport(PLExport v) {
		plExport = v;
	}

	// ------------------- utility methods -------------------
	/**
	 * Prints to the output writer {@link #out} indentation spaces
	 * (according to {@link #indent}) followed by the given text.
	 */
	protected void print(String text) {
		for (int i = 0; i < indent; i++) {
			out.print(" ");
		}
		out.print(text);
	}

	/** 
	 * Prints <code>text</code> to the output writer {@link #out} (no
	 * indentation).
	 */
	protected void niprint(String text) {
		out.print(text);
	}

	/** 
	 * Prints <code>text</code> followed by newline to the output
	 * writer {@link #out} (no indentation).
	 */
	protected void niprintln(String text) {
		out.println(text);
	}

	/**
	 * Prints to the output writer {@link #out} indentation spaces
	 * (according to {@link #indent}) followed by the given text
	 * followed by a newline.
	 */
	protected void println(String text) {
		for (int i = 0; i < indent; i++) {
			out.print(" ");
		}
		out.println(text);
	}
	
    /**
     * The ProjectModificationWatcher watches a PlayPen's components and
     * business model for changes.  When it detects any, it marks the
     * project dirty.
     * 
     * <p>Note: when we implement proper undo/redo support, this class should
     * be replaced with a hook into that system.
     */
    private class ProjectModificationWatcher implements SQLObjectListener,
            ComponentListener, ContainerListener {

        /**
         * Sets up a new modification watcher on the given playpen. 
         */
        public ProjectModificationWatcher(PlayPen pp) {
            try {
                ArchitectUtils.listenToHierarchy(this, pp.getDatabase());
            } catch (ArchitectException e) {
                logger.error("Can't listen to business model for changes", e);
            }
            PlayPenContentPane ppcp = pp.contentPane;
            ppcp.addComponentListener(this);
            ppcp.addContainerListener(this);
            for (int i = 0; i < ppcp.getComponentCount(); i++) {
                ppcp.getComponent(i).addComponentListener(this);
                if (ppcp.getComponent(i) instanceof Container) {
                    ((Container) ppcp.getComponent(i)).addContainerListener(this);
                }
            }
        }

        /** Marks project dirty, and starts listening to new kids. */
        public void dbChildrenInserted(SQLObjectEvent e) {
            setModified(true);
            SQLObject[] newKids = e.getChildren();
            for (int i = 0; i < newKids.length; i++) {
                try {
                    ArchitectUtils.listenToHierarchy(this, newKids[i]);
                } catch (ArchitectException e1) {
                    logger.error("Couldn't listen to SQLObject hierarchy rooted at "+newKids[i], e1);
                }
            }
        }

        /** Marks project dirty, and stops listening to removed kids. */
        public void dbChildrenRemoved(SQLObjectEvent e) {
            setModified(true);
            SQLObject[] oldKids = e.getChildren();
            for (int i = 0; i < oldKids.length; i++) {
                oldKids[i].removeSQLObjectListener(this);
            }
        }

        /** Marks project dirty. */
        public void dbObjectChanged(SQLObjectEvent e) {
            setModified(true);
        }

        /** Marks project dirty and listens to new hierarchy. */
        public void dbStructureChanged(SQLObjectEvent e) {
            try {
                ArchitectUtils.listenToHierarchy(this, e.getSQLSource());
            } catch (ArchitectException e1) {
                logger.error("dbStructureChanged listener: Failed to listen to new project hierarchy", e1);
            }
        }

        public void componentResized(ComponentEvent e) {
            setModified(true);
        }

        public void componentMoved(ComponentEvent e) {
            setModified(true);
        }

        public void componentShown(ComponentEvent e) {
            // nothing to do
        }

        public void componentHidden(ComponentEvent e) {
            // nothing to do
        }

        public void componentAdded(ContainerEvent e) {
            e.getChild().addComponentListener(this);
            if (e.getChild() instanceof Container) {
                ((Container) e.getChild()).addContainerListener(this);
            }
        }
        
        public void componentRemoved(ContainerEvent e) {
            e.getChild().removeComponentListener(this);
            if (e.getChild() instanceof Container) {
                ((Container) e.getChild()).removeContainerListener(this);
            }
        }
    }
    
    /**
     * See {@link #modified}.
     */
    public boolean isModified() {
        return modified;
    }
    
    /**
     * See {@link #modified}.
     */
    public void setModified(boolean modified) {
        if (logger.isDebugEnabled()) logger.debug("Project modified: "+modified);
        this.modified = modified;
    }
}