import * as React from "react";
import clsx from "clsx";
import mergeRefs from "react-merge-refs";

type CheckboxProps = {
  className?: string;
  label?: string | JSX.Element;
  rootRef?: React.ForwardedRef<HTMLLabelElement>;
  indeterminate?: boolean;
} & Omit<React.InputHTMLAttributes<HTMLInputElement>, "className" | "type">;

export const Checkbox = React.forwardRef<HTMLInputElement, CheckboxProps>(
  (props, ref) => {
    const { label, className, rootRef, indeterminate, ...inputProps } = props;

    const checkboxRef = React.useRef<HTMLInputElement>(null);
    React.useEffect(() => {
      if (checkboxRef.current) {
        checkboxRef.current.indeterminate = !!indeterminate;
      }
    }, [indeterminate]);

    return (
      <label className={clsx(className, "flex items-center")} ref={rootRef}>
        <input
          {...inputProps}
          className="form-control"
          type="checkbox"
          ref={mergeRefs([ref, checkboxRef])}
          style={{
            marginInlineEnd: "var(--dremio--spacing--1)",
          }}
        />
        {label}
      </label>
    );
  },
);
