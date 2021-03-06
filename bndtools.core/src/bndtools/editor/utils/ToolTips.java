package bndtools.editor.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Text;

import aQute.bnd.help.Syntax;
import bndtools.Logger;
import bndtools.api.ILogger;

public class ToolTips {
    private static final ILogger logger = Logger.getLogger();

    static private String getStrippedExample(Syntax syntax, String constant) {
        String example = syntax.getExample();
        if ((example == null) || (example.trim().length() == 0)) {
            return null;
        }

        Pattern p = Pattern.compile("^(\\s*" + Pattern.quote(constant.trim()) + "\\s*)(:|=|:=)(\\s*)(.*?)\\s*$");
        Matcher matcher = p.matcher(example);
        if (matcher.matches()) {
            return example.substring(matcher.start(4), matcher.end(4));
        }

        return example;
    }

    /**
     * Setup the message and the tooltip of a control. The Syntax class of bnd is used to determine these.
     * 
     * @param control
     *            the control
     * @param constant
     *            the constant, as mentioned in aQute.bnd.osgi.Constants
     */
    static public void setupMessageAndToolTipFromSyntax(Control control, String constant) {
        Syntax syntax = Syntax.HELP.get(constant);
        if (syntax == null) {
            logger.logError("No bnd syntax found for " + constant, null);
            syntax = new Syntax(constant, "Description of " + constant, constant + ": Example for " + constant, null, null);
        }

        String values = syntax.getValues();
        if (values != null) {
            /* filter out macros */
            values = values.replaceAll("\\$\\{[^\\}]*\\}", "");
            values = values.replaceAll(",\\s*,", ",");
            values = values.replaceAll("(^,|,$)", "");
        }
        if ((values == null) || (values.trim().length() == 0)) {
            values = "";
        } else {
            values = "\n\nProposed Values:\n" + values.trim().replaceAll("\\s*,\\s*", ", ");
        }

        String message = "";
        String examples = getStrippedExample(syntax, constant);
        if (examples == null) {
            message = "";
            examples = "";
        } else {
            message = examples;
            examples = "\n\nExample:\n" + examples;
        }

        if (control instanceof Text) {
            Text text = (Text) control;
            String tt = syntax.getLead() + values + examples;
            text.setToolTipText(tt);
            text.setMessage(message);
        }
    }
}
