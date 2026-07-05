package com.kk.greet.ui;

import java.awt.BorderLayout;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

// Encoding canary — must stay compilable: çğıöşü ÇĞİÖŞÜ
/**
 * DS component that opens a small Swing window on activation. Every visible
 * text comes from the external resource bundle (configs/lang) via
 * {@link Messages} — no user-facing literals in code.
 *
 * In GREET_MODE=check runs the window is skipped (headless/CI); the localized
 * title is still printed, so scripted checks can assert language selection.
 */
@Component
public class GreetFrame {

	private JFrame frame;

	@Activate
	public void start() {
		System.out.println("GreetFrame.start() lang=" + Messages.language()
				+ " title=" + Messages.get("ui.title"));

		if ("check".equalsIgnoreCase(System.getenv("GREET_MODE"))) {
			return;
		}

		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				JPanel fields = new JPanel(new GridLayout(0, 2, 8, 8));
				fields.add(new JLabel(Messages.get("ui.label.name")));
				fields.add(new JTextField(Messages.get("ui.field.name"), 16));
				fields.add(new JLabel(Messages.get("ui.label.city")));
				fields.add(new JTextField(Messages.get("ui.field.city"), 16));

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
