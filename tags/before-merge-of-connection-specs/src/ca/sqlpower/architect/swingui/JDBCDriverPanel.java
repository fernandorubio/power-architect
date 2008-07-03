package ca.sqlpower.architect.swingui;

import ca.sqlpower.architect.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.tree.*;
import java.io.*;
import java.util.jar.*;
import java.util.zip.*;
import java.util.Iterator;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import org.apache.log4j.Logger;

public class JDBCDriverPanel extends JPanel implements ArchitectPanel {

	private static final Logger logger = Logger.getLogger(JDBCDriverPanel.class);

	/**
	 * The current session (whose JDBC driver search path we're editting).
	 */
	protected ArchitectSession session;

	/**
	 * This view shows the driver JAR files and the JDBC drivers they
	 * contain.
	 */
	protected JTree driverTree;

	/**
	 * This tree model holds the registered JAR files under the root,
	 * and lists the JDBC driver classes as children of each JAR file.
	 */
	protected DefaultTreeModel dtm;

	/**
	 * The file choosed used by the add action.
	 */
	JFileChooser fileChooser;

	/**
	 * progress bar stuff
	 */
    protected JProgressBar progressBar;
	protected javax.swing.Timer timer;
	protected boolean doneLoadingJDBC;
	protected JLabel progressLabel;

	protected JButton addButton;
	protected JButton delButton;

	public JDBCDriverPanel(ArchitectSession session) {
		this.session = session;
		setup();
		try {
			revertToUserSettings();
		} catch (ArchitectException e) {
			logger.error("revertToUserSettings failed.", e);
		}			
	}

	public void setup() {
		fileChooser = new JFileChooser();

		setLayout(new BorderLayout());
		dtm = new DefaultTreeModel(new DefaultMutableTreeNode("The Root"));
		driverTree = new JTree(dtm);
		driverTree.setRootVisible(false);
		add(new JScrollPane(driverTree), BorderLayout.CENTER);
		
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
		buttonPanel.add(addButton = new JButton(new AddAction()));
		buttonPanel.add(delButton = new JButton(new DelAction()));
		add(buttonPanel, BorderLayout.SOUTH);

		JPanel progressPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
		progressBar = new JProgressBar();
		progressBar.setStringPainted(true); //get space for the string
		progressBar.setVisible(false);		
		progressPanel.add(progressBar);
		progressLabel = new JLabel("Scanning for JDBC Drivers...");
		progressLabel.setVisible(false);
		progressPanel.add(progressLabel);
		add(progressPanel, BorderLayout.NORTH);
	}

	protected void revertToUserSettings() throws ArchitectException {
		dtm.setRoot(new DefaultMutableTreeNode());		
		LoadJDBCDrivers ljd = new LoadJDBCDrivers(session.getDriverJarList());
		LoadJDBCDriversWorker worker = new LoadJDBCDriversWorker(ljd);
        ProgressWatcher watcher = new ProgressWatcher(progressBar,ljd,progressLabel);
		new Thread(worker).start();
	}

	/**
	 * Copies the pathnames to all the JAR files in the tree into a
	 * list and then passes that list to
	 * ArchitectSession.setDriverJarList().
	 */
	public void applyChanges() {
		ArrayList drivers = new ArrayList(dtm.getChildCount(dtm.getRoot()));
		for (int i = 0, n = dtm.getChildCount(dtm.getRoot()); i < n; i++) {
			drivers.add(((DefaultMutableTreeNode) dtm.getChild(dtm.getRoot(), i)).getUserObject());
		}
		session.setDriverJarList(drivers);
	}

	/**
	 * Does nothing.
	 */
	public void discardChanges() {
        // nothing to discard
	}

	protected class AddAction extends AbstractAction {
		public AddAction() {
			super("Add...");
		}

		public void actionPerformed(ActionEvent e) {			
			try {
				fileChooser.addChoosableFileFilter(ASUtils.JAR_ZIP_FILE_FILTER);
				int returnVal = fileChooser.showOpenDialog(JDBCDriverPanel.this);
				if(returnVal == JFileChooser.APPROVE_OPTION) {
					List list = new ArrayList();
					list.add(fileChooser.getSelectedFile().getAbsolutePath());
					LoadJDBCDrivers ljd = new LoadJDBCDrivers(list);
				    LoadJDBCDriversWorker worker = new LoadJDBCDriversWorker(ljd);
					new ProgressWatcher(progressBar,ljd,progressLabel);
					new Thread(worker).start();
				}
			} catch (ArchitectException ex) {
				logger.error("AddAction.actionPerformed() problem.", ex);
			}
		}
	}

	protected class DelAction extends AbstractAction {
		public DelAction() {
			super("Remove");
		}

		public void actionPerformed(ActionEvent e) {
			TreePath p = driverTree.getSelectionPath();
			if (p != null && p.getPathCount() >= 2) {
				dtm.removeNodeFromParent((MutableTreeNode) p.getPathComponent(1));
			}
		}
	}
		
	protected class LoadJDBCDriversWorker implements Runnable {
		LoadJDBCDrivers ljd;
		LoadJDBCDriversWorker (LoadJDBCDrivers ljd) {
			this.ljd = ljd;	
		}
		public void run() {
			ljd.execute();
		}
	}

	protected class LoadJDBCDrivers implements Monitorable  {

		public boolean finished = false;		
		private List driverJarList = null;
		

		private int jarCount = 0; // which member of the JAR file list are we currently processing
		private JarFile jf = null;
		private JDBCScanClassLoader cl = null;		

		public LoadJDBCDrivers (List driverJarList) throws ArchitectException {
			this.driverJarList = driverJarList;
			logger.debug("in constructor, setting finished to false...");
			finished = false;
		}

		public Integer getJobSize() throws ArchitectException {			
			return new Integer(driverJarList.size() * 1000);
		}
		
		public int getProgress() throws ArchitectException {
			double fraction = 0.0;
			if (cl != null) {
				fraction = cl.getFraction();
			}
		    int progress = (jarCount - 1) * 1000 + (int) (fraction * 1000.0);
			if (logger.isDebugEnabled()) logger.debug("******************* progress is: " + progress + " of " + getJobSize());
			return progress;
		}
		
		public boolean isFinished() throws ArchitectException {
			return finished;
		}
		
		public void execute() {	        
			try {
				Iterator it = driverJarList.iterator();
				while (it.hasNext()) {
					// initialize counters
					jarCount++;
					logger.debug("**************** processin file #" + jarCount + " of " + driverJarList.size());
					String path = (String) it.next();
					addJarFile(new File(path));
				}
				finished = true;
				logger.debug("done loading (normal operation), setting finished to true.");
			} catch ( Exception exp ) {
				logger.error("something went wrong in LoadJDBCDrivers worker thread!",exp);
			} finally {
				finished = true;
				logger.debug("done loading (error condition), setting finished to true.");
			}
		}

		public void addJarFile(File file) {
			DefaultMutableTreeNode root = (DefaultMutableTreeNode) dtm.getRoot();
			try {
				jf = new JarFile(file);
				cl = new JDBCScanClassLoader(jf);
				List driverClasses = cl.scanForDrivers();
				System.out.println("Found drivers: "+driverClasses);
				DefaultMutableTreeNode node = new DefaultMutableTreeNode(file.getPath());
				dtm.insertNodeInto(node, root, root.getChildCount());
				Iterator it = driverClasses.iterator();
				while (it.hasNext()) {
					DefaultMutableTreeNode child = new DefaultMutableTreeNode(it.next());
					dtm.insertNodeInto(child, node, node.getChildCount());
				}
				TreePath path = new TreePath(node.getPath());
				driverTree.expandPath(path);
				driverTree.scrollPathToVisible(path);
			} catch (IOException ex) {
				logger.warn("I/O Error reading JAR file",ex);
				final Exception fex = ex;
				final File ffile = file;
				SwingUtilities.invokeLater(new Runnable() {
					public void run() {
						JOptionPane.showMessageDialog(JDBCDriverPanel.this, "Could not read JAR file \""
													  +ffile.getPath()+"\"\n"+fex.getMessage());
					}
				});				
			}
		}
	}

	/**
	 * Scans a jar file for instances of java.sql.Driver.
	 */
	protected class JDBCScanClassLoader extends ClassLoader {

		JarFile jf;
		List drivers;
		int count = 0;
		

		/**
		 * Creates a class loader that uses this class's class loader
		 * as its parent.
		 */
		public JDBCScanClassLoader(JarFile jf) {
			super();
			this.jf = jf;
		}

		public synchronized double getFraction() {
			double retval = 0.0;
			if (jf != null) {
				retval = (double)count/(double)jf.size();
			}
			return retval;
		}

		/**
		 * Returns a list of Strings naming the subclasses of
		 * java.sql.Driver which exist in this class loader's jar
		 * file.
		 */
		public List scanForDrivers() {
			drivers = new LinkedList();
			logger.debug("********* " + jf.getName() + " has " + jf.size() + " files.");
			for (Enumeration entries = jf.entries(); entries.hasMoreElements(); ) {
				count++;
				ZipEntry ent = (ZipEntry) entries.nextElement();
				if (ent.getName().endsWith(".class")) {
					try {
						// drop the .class from the name
						String [] s = ent.getName().split("\\.");
						// look for the class using dots instead of slashes
						findClass(s[0].replace('/','.'));
					} catch (ClassFormatError ex) {
						logger.warn("JAR entry "+ent.getName()+" ends in .class but is not a class", ex);
					} catch (NoClassDefFoundError ex) {
						logger.warn("JAR does not contain dependency: " + ent.getName());
					} catch (Throwable ex) {
						logger.warn("Unexpected exception while scanning JAR file "+jf.getName(), ex);
					}
				}
			}
			//jf.close();
			return drivers;
		}

		/**
		 * Searches this ClassLoader's jar file for the given class.
		 *
		 * @throws ClassNotFoundException if the class can't be
		 * located.
		 */
		protected Class findClass(String name)
			throws ClassNotFoundException {
			logger.debug("Looking for class "+name);
			try {
				ZipEntry ent = jf.getEntry(name.replace('.', '/')+".class");
				if (ent == null) {
					throw new ClassNotFoundException("No class file "+name+" is in my jar file");
				}
				// can we find out here if it was already loaded???
				Class clazz = findLoadedClass(name);
				if (clazz != null) {
					return clazz;
				}
				// haven't seen this before, so go get it...
				InputStream is = jf.getInputStream(ent);
				return readAndCheckClass(is, (int) ent.getSize(), name);
			} catch (IOException ex) {
				throw new ClassNotFoundException("IO Exception reading class from jar file", ex);
			}
		}

		private Class readAndCheckClass(InputStream is, int size, String expectedName)
			throws IOException, ClassFormatError {
			byte[] buf = new byte[size];
			int start = 0, n;
			while ( (n = is.read(buf, start, size-start)) > 0) {
				start += n;
			}
			if ( (start + n) != size ) {
				logger.warn("Only read "+(start+n)+" bytes of class "
							+expectedName+" from JAR file; exptected "+size);
			}
			Class clazz = defineClass(expectedName, buf, 0, start + n);
			if (java.sql.Driver.class.isAssignableFrom(clazz)) {
				logger.info("Found jdbc driver "+clazz.getName());
				drivers.add(clazz.getName());
			}
			return clazz;
		}

	}
}