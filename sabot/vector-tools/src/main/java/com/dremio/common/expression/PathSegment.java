/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.common.expression;

import com.dremio.common.expression.BasePath.SchemaPathVisitor;
import com.dremio.exec.record.TypedFieldId;
import java.util.Optional;

public abstract class PathSegment {

  @SuppressWarnings("checkstyle:VisibilityModifier")
  protected PathSegment child;

  private int hash;

  protected PathSegment(PathSegment child) {
    this.child = child;
  }

  public abstract PathSegment cloneWithNewChild(PathSegment segment);

  public abstract PathSegment cloneWithoutChild();

  @Override
  public abstract PathSegment clone();

  public abstract <IN, OUT> OUT accept(SchemaPathVisitor<IN, OUT> visitor, IN in);

  public static final class ArraySegment extends PathSegment {
    private final Optional<Integer> optionalIndex;

    public ArraySegment(Optional<Integer> optionalIndex, PathSegment child) {
      super(child);

      if (optionalIndex.isPresent() && optionalIndex.get() < 0) {
        throw new IllegalArgumentException(
            "Expected a non negative index for array indexing. Got: " + optionalIndex.get());
      }

      this.optionalIndex = optionalIndex;
    }

    public ArraySegment(String numberAsText, PathSegment child) {
      this(Integer.parseInt(numberAsText), child);
    }

    public ArraySegment(int index, PathSegment child) {
      this(Optional.of(index), child);
    }

    public ArraySegment(PathSegment child) {
      this(Optional.empty(), child);
    }

    public ArraySegment(int index) {
      this(Optional.of(index), null);
    }

    public boolean hasIndex() {
      return optionalIndex.isPresent();
    }

    public int getOptionalIndex() {
      return optionalIndex.orElse(-1);
    }

    @Override
    public PathSegmentType getType() {
      return PathSegmentType.ARRAY_INDEX;
    }

    @Override
    public PathSegment getPathSegment() {
      return this;
    }

    @Override
    public <IN, OUT> OUT accept(SchemaPathVisitor<IN, OUT> visitor, IN in) {
      return visitor.visitArray(this, in);
    }

    @Override
    public String toString() {
      return "ArraySegment [index=" + getOptionalIndex() + ", getChild()=" + getChild() + "]";
    }

    @Override
    public int segmentHashCode() {
      return getOptionalIndex();
    }

    @Override
    public boolean segmentEquals(PathSegment obj) {
      if (this == obj) {
        return true;
      } else if (obj == null) {
        return false;
      } else if (obj instanceof ArraySegment) {
        return optionalIndex.equals(((ArraySegment) obj).optionalIndex);
      }
      return false;
    }

    @Override
    public PathSegment clone() {
      PathSegment seg =
          !optionalIndex.isPresent()
              ? new ArraySegment(null)
              : new ArraySegment(optionalIndex.get());
      if (child != null) {
        seg.setChild(child.clone());
      }
      return seg;
    }

    @Override
    public ArraySegment cloneWithNewChild(PathSegment newChild) {
      ArraySegment seg =
          !optionalIndex.isPresent()
              ? new ArraySegment(null)
              : new ArraySegment(optionalIndex.get());
      if (child != null) {
        seg.setChild(child.cloneWithNewChild(newChild));
      } else {
        seg.setChild(newChild);
      }
      return seg;
    }

    @Override
    public PathSegment cloneWithoutChild() {
      if (isLastPath()) {
        return null;
      }

      return new ArraySegment(optionalIndex, child.cloneWithoutChild());
    }
  }

  public static final class NameSegment extends PathSegment {
    private final String path;

    public NameSegment(CharSequence n, PathSegment child) {
      super(child);
      this.path = n.toString();
    }

    public NameSegment(CharSequence n) {
      this(n, null);
    }

    public String getPath() {
      return path;
    }

    @Override
    public NameSegment cloneWithoutChild() {
      if (isLastPath()) {
        return null;
      }

      return new NameSegment(path, child.cloneWithoutChild());
    }

    @Override
    public PathSegmentType getType() {
      return PathSegmentType.NAME;
    }

    @Override
    public PathSegment getPathSegment() {
      return this;
    }

    @Override
    public <IN, OUT> OUT accept(SchemaPathVisitor<IN, OUT> visitor, IN in) {
      return visitor.visitName(this, in);
    }

    @Override
    public String toString() {
      return "NameSegment [path=" + path + ", getChild()=" + getChild() + "]";
    }

    @Override
    public int segmentHashCode() {
      return ((path == null) ? 0 : path.toLowerCase().hashCode());
    }

    @Override
    public boolean segmentEquals(PathSegment obj) {
      if (this == obj) {
        return true;
      } else if (obj == null) {
        return false;
      } else if (getClass() != obj.getClass()) {
        return false;
      }

      NameSegment other = (NameSegment) obj;
      if (path == null) {
        return other.path == null;
      }
      return path.equalsIgnoreCase(other.path);
    }

    @Override
    public NameSegment clone() {
      NameSegment s = new NameSegment(this.path);
      if (child != null) {
        s.setChild(child.clone());
      }
      return s;
    }

    @Override
    public NameSegment cloneWithNewChild(PathSegment newChild) {
      NameSegment s = new NameSegment(this.path);
      if (child != null) {
        s.setChild(child.cloneWithNewChild(newChild));
      } else {
        s.setChild(newChild);
      }
      return s;
    }
  }

  public static final class ArraySegmentInputRef extends PathSegment {
    private final String path;
    private TypedFieldId fieldId;

    public ArraySegmentInputRef(CharSequence n, PathSegment child) {
      super(child);
      this.path = n.toString();
    }

    public ArraySegmentInputRef(CharSequence n) {
      this(n, null);
    }

    public String getPath() {
      return path;
    }

    public TypedFieldId getFieldId() {
      return fieldId;
    }

    public void setFieldId(TypedFieldId fieldId) {
      this.fieldId = fieldId;
    }

    @Override
    public ArraySegmentInputRef cloneWithoutChild() {
      if (isLastPath()) {
        return null;
      }

      return new ArraySegmentInputRef(path, child.cloneWithoutChild());
    }

    @Override
    public PathSegmentType getType() {
      return PathSegmentType.ARRAY_INDEX_REF;
    }

    @Override
    public PathSegment getPathSegment() {
      return this;
    }

    @Override
    public <IN, OUT> OUT accept(SchemaPathVisitor<IN, OUT> visitor, IN in) {
      return visitor.visitArrayInput(this, in);
    }

    @Override
    public String toString() {
      return "ArrayInputRef [path=" + path + ", getChild()=" + getChild() + "]";
    }

    @Override
    public int segmentHashCode() {
      return ((path == null) ? 0 : path.toLowerCase().hashCode());
    }

    @Override
    public boolean segmentEquals(PathSegment obj) {
      if (this == obj) {
        return true;
      } else if (obj == null) {
        return false;
      } else if (getClass() != obj.getClass()) {
        return false;
      }

      ArraySegmentInputRef other = (ArraySegmentInputRef) obj;
      if (path == null) {
        return other.path == null;
      }
      return path.equalsIgnoreCase(other.path);
    }

    @Override
    public ArraySegmentInputRef clone() {
      ArraySegmentInputRef s = new ArraySegmentInputRef(this.path);
      if (child != null) {
        s.setChild(child.clone());
      }
      return s;
    }

    @Override
    public ArraySegmentInputRef cloneWithNewChild(PathSegment newChild) {
      ArraySegmentInputRef s = new ArraySegmentInputRef(this.path);
      if (child != null) {
        s.setChild(child.cloneWithNewChild(newChild));
      } else {
        s.setChild(newChild);
      }
      return s;
    }
  }

  public abstract PathSegment getPathSegment();

  public abstract PathSegmentType getType();

  public NameSegment getNameSegment() {
    PathSegment current = getPathSegment();
    if (current instanceof NameSegment) {
      return (NameSegment) current;
    } else {
      throw new UnsupportedOperationException();
    }
  }

  public ArraySegment getArraySegment() {
    PathSegment current = getPathSegment();
    if (current instanceof ArraySegment) {
      return (ArraySegment) current;
    } else {
      throw new UnsupportedOperationException();
    }
  }

  public ArraySegmentInputRef getArrayInputRef() {
    PathSegment current = getPathSegment();
    if (current instanceof ArraySegmentInputRef) {
      return (ArraySegmentInputRef) current;
    } else {
      throw new UnsupportedOperationException();
    }
  }

  public boolean isLastPath() {
    return child == null;
  }

  public PathSegment getChild() {
    return child;
  }

  void setChild(PathSegment child) {
    this.child = child;
  }

  protected abstract int segmentHashCode();

  protected abstract boolean segmentEquals(PathSegment other);

  @Override
  public int hashCode() {
    int h = hash;
    if (h == 0) {
      h = segmentHashCode();
      h = 31 * h + ((child == null) ? 0 : child.hashCode());
      hash = h;
    }
    return h;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }

    PathSegment other = (PathSegment) obj;
    if (!segmentEquals(other)) {
      return false;
    } else if (child == null) {
      return (other.child == null);
    } else {
      return child.equals(other.child);
    }
  }

  /**
   * Check if another path is contained in this one. This is useful for 2 cases. The first is
   * checking if the other is lower down in the tree, below this path. The other is if a path is
   * actually contained above the current one.
   *
   * <p>Examples: [a] . contains( [a.b.c] ) returns true [a.b.c] . contains( [a] ) returns true
   *
   * <p>This behavior is used for cases like scanning json in an event based fashion, when we arrive
   * at a node in a complex type, we will know the complete path back to the root. This method can
   * be used to determine if we need the data below. This is true in both the cases where the column
   * requested from the user is below the current node (in which case we may ignore other nodes
   * further down the tree, while keeping others). This is also the case if the requested path is
   * further up the tree, if we know we are at position a.b.c and a.b was a requested column, we
   * need to scan all of the data at and below the current a.b.c node.
   *
   * @param otherSeg - path segment to check if it is contained below this one.
   * @return - is this a match
   */
  public boolean contains(PathSegment otherSeg) {
    if (this == otherSeg) {
      return true;
    }
    if (otherSeg == null) {
      return false;
    }
    // TODO - fix this in the future to match array segments are part of the path
    // the current behavior to always return true when we hit an array may be useful in some cases,
    // but we can get better performance in the JSON reader if we avoid reading unwanted elements in
    // arrays
    if (otherSeg.getType().equals(PathSegmentType.ARRAY_INDEX)
        || this.getType().equals(PathSegmentType.ARRAY_INDEX)) {
      return true;
    }
    if (getClass() != otherSeg.getClass()) {
      return false;
    }

    if (!segmentEquals(otherSeg)) {
      return false;
    }
    if (child == null || otherSeg.child == null) {
      return true;
    }
    return child.contains(otherSeg.child);
  }

  public enum PathSegmentType {
    ARRAY_INDEX,
    ARRAY_INDEX_REF,
    NAME
  }
}
