/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa.tray;

import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import nl.infcomtec.advswing.ACheckBox;
import nl.infcomtec.advswing.AButton;
import nl.infcomtec.advswing.ALabel;
import nl.infcomtec.advswing.APanel;
import nl.infcomtec.advswing.EzAction;
import nl.infcomtec.advswing.GBCompass;
import nl.infcomtec.mitsa.Participant;

/**
 * First-run onboarding picker: a checkbox per known MITSA-compatible
 * app, from participants.json. Blocks (modal) until the user clicks
 * Install.
 */
public class OnboardingDialog {

    public static List<Participant> pick(List<Participant> participants) {
        final List<Participant> chosen = new ArrayList<Participant>();
        final JDialog dialog = new JDialog((java.awt.Frame) null, "Welcome to MITSA", true);

        APanel root = new APanel();
        root.add(new ALabel("Pick which apps you'd like to install:"), GBCompass.north());

        JPanel checkPanel = new JPanel(new GridLayout(0, 1));
        final List<ACheckBox> boxes = new ArrayList<ACheckBox>();
        for (Participant p : participants) {
            ACheckBox box = new ACheckBox();
            box.setText(p.id + " - " + p.description);
            boxes.add(box);
            checkPanel.add(box);
        }
        root.add(new JScrollPane(checkPanel), GBCompass.center());

        AButton install = new AButton(new EzAction("Install") {
            @Override
            public void actionPerformed(ActionEvent e) {
                for (int i = 0; i < boxes.size(); i++) {
                    if (boxes.get(i).isSelected()) {
                        chosen.add(participants.get(i));
                    }
                }
                dialog.dispose();
            }
        });
        root.add(install, GBCompass.south());

        dialog.add(root);
        dialog.setSize(480, 320);
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);

        return chosen;
    }
}
