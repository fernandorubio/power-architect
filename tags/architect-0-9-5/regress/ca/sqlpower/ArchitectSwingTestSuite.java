package ca.sqlpower;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import ca.sqlpower.architect.layout.TestFruchtermanReingoldForceLayout;
import ca.sqlpower.architect.swingui.TestArchitectFrame;
import ca.sqlpower.architect.swingui.TestAutoLayoutAction;
import ca.sqlpower.architect.swingui.TestBasicRelationshipUI;
import ca.sqlpower.architect.swingui.TestColumnEditPanel;
import ca.sqlpower.architect.swingui.TestCompareDMPanel;
import ca.sqlpower.architect.swingui.TestDBTree;
import ca.sqlpower.architect.swingui.TestPlayPen;
import ca.sqlpower.architect.swingui.TestPlayPenComponent;
import ca.sqlpower.architect.swingui.TestRelationship;
import ca.sqlpower.architect.swingui.TestSwingUIProject;
import ca.sqlpower.architect.swingui.TestTableEditPane;
import ca.sqlpower.architect.swingui.TestTablePane;
import ca.sqlpower.architect.swingui.action.TestDeleteSelectedAction;
import ca.sqlpower.architect.undo.TestSQLObjectUndoableEventAdapter;
import ca.sqlpower.architect.undo.TestUndoManager;

/**
 * This suite consists of the GUI tests whose class names do not
 * conform to the standard junit class name format *Test.java. See
 * the {@link ArchitectAutoTests} class for the rest of the suite.
 */
public class ArchitectSwingTestSuite extends TestCase {

	public static Test suite() {
		TestSuite suite = new TestSuite("Test for Architect's Swing GUI");
		//$JUnit-BEGIN$
		suite.addTestSuite(TestArchitectFrame.class);
		suite.addTestSuite(TestAutoLayoutAction.class);
		suite.addTestSuite(TestBasicRelationshipUI.class);
		suite.addTestSuite(TestDBTree.class);
		suite.addTestSuite(TestColumnEditPanel.class);
		suite.addTestSuite(TestDeleteSelectedAction.class);
		suite.addTestSuite(TestCompareDMPanel.class);
		suite.addTestSuite(TestFruchtermanReingoldForceLayout.class);
		suite.addTestSuite(TestRelationship.class);
		suite.addTestSuite(TestPlayPen.class);
		suite.addTestSuite(TestPlayPenComponent.class);
		suite.addTestSuite(TestSwingUIProject.class);
		suite.addTestSuite(TestSQLObjectUndoableEventAdapter.class);
		suite.addTestSuite(TestTableEditPane.class);
		suite.addTestSuite(TestTablePane.class);
		suite.addTestSuite(TestUndoManager.class);
		//$JUnit-END$
		return suite;
	}

}