package name.neilbartlett.eclipse.bndtools.utils;

import java.util.Locale;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

public class FileExtensionFilter extends ViewerFilter {

	private String fTargetExtension;

	public FileExtensionFilter(String targetExtension) {
		fTargetExtension = targetExtension;
	}

	public boolean select(Viewer viewer, Object parent, Object element) {
		if (element instanceof IFile) {
			return ((IFile) element).getName().toLowerCase(Locale.ENGLISH).endsWith("." + fTargetExtension); //$NON-NLS-1$
		}

		if (element instanceof IProject && !((IProject) element).isOpen())
			return false;

		if (element instanceof IContainer) { // i.e. IProject, IFolder
			try {
				IResource[] resources = ((IContainer) element).members();
				for (int i = 0; i < resources.length; i++) {
					if (select(viewer, parent, resources[i]))
						return true;
				}
			} catch (CoreException e) {
			}
		}
		return false;
	}

}