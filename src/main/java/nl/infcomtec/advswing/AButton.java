package nl.infcomtec.advswing;

import javax.swing.JButton;

/**
 *
 * @author walter
 */
public class AButton extends JButton {

    public AButton(EzAction a) {
        super(a);
        if (null != a && null != a.font) {
            setFont(a.font);
        }
        if (null != a && null != a.background) {
            setBackground(a.background);
        }
        if (null != a && null != a.foreground) {
            setForeground(a.foreground);
        }
    }

    public AButton withProps(SwingProps props) {
        props.apply(this);
        return this;
    }

}
