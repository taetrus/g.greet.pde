package com.kk.greet.ui;

import java.awt.BorderLayout;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

import net.miginfocom.layout.LayoutUtil;
import net.miginfocom.swing.MigLayout;

// Encoding canary — must stay compilable: çğıöşü ÇĞİÖŞÜ
/**
 * DS component that opens a small Swing window on activation. Every visible
 * text comes from the external resource bundle (configs/lang) via
 * {@link Messages} — no user-facing literals in code.
 *
 * The layout uses MigLayout from lib/ (a Bundle-ClassPath nested jar), proving
 * that bundle-embedded third-party libraries survive the build + obfuscation
 * pipeline.
 *
 * In GREET_MODE=check runs the window is skipped (headless/CI); the localized
 * title and the MigLayout version are still printed, so scripted checks can
 * assert language selection and nested-jar classloading.
 */
@Component
public class GreetFrame {

	private JFrame frame;

	@Activate
	public void start() {
		System.out.println("GreetFrame.start() lang=" + Messages.language()
				+ " title=" + Messages.get("ui.title")
				+ " miglayout=" + LayoutUtil.getVersion());

		if ("check".equalsIgnoreCase(System.getenv("GREET_MODE"))) {
			return;
		}

		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				JPanel fields = new JPanel(new MigLayout("wrap 2", "[right]rel[grow,fill,200::]", ""));
				fields.add(new JLabel(Messages.get("ui.label.name")));
				fields.add(new JTextField(Messages.get("ui.field.name")));
				fields.add(new JLabel(Messages.get("ui.label.city")));
				fields.add(new JTextField(Messages.get("ui.field.city")));

				JLabel greeting = new JLabel(Messages.get("ui.greeting"), JLabel.CENTER);
				greeting.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

				JPanel content = new JPanel(new BorderLayout());
				content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
				content.add(fields, BorderLayout.CENTER);
				content.add(greeting, BorderLayout.SOUTH);

				JFrame f = new JFrame(Messages.get("ui.title"));
				// DISPOSE, not EXIT: closing the window must not kill the OSGi framework.
				f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
				f.setContentPane(content);
				f.pack();
				f.setLocationByPlatform(true);
				f.setVisible(true);
				frame = f;
			}
		});
	}

	@Deactivate
	public void stop() {
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				if (frame != null) {
					frame.dispose();
					frame = null;
				}
			}
		});
	}

}
