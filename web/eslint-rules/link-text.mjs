/**
 * Link text must state its fact ("CHAPS reopens Tue 29 Dec 2026"), never point at itself. Fails on
 * <Link> or <a> whose text (or aria-label) is "here", "read more" or "learn more", or that says
 * "click" anywhere. Text built in expressions can't be checked here; the snapshot tests read it.
 */

const VAGUE = new Set(["here", "click here", "read more", "learn more", "more", "this link"]);

function normalise(text) {
  return text
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s]/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
}

export function isVagueLinkText(text) {
  const words = normalise(text);
  return VAGUE.has(words) || /\bclick/.test(words);
}

function elementName(node) {
  const name = node.openingElement.name;
  return name.type === "JSXIdentifier" ? name.name : null;
}

/** The literal text inside a JSX element, including nested elements' literal text. */
function literalText(node) {
  return node.children
    .map((child) => {
      if (child.type === "JSXText") {
        return child.value;
      }
      if (child.type === "JSXExpressionContainer" && child.expression.type === "Literal") {
        return String(child.expression.value);
      }
      if (child.type === "JSXElement") {
        return literalText(child);
      }
      return " ";
    })
    .join("");
}

function ariaLabel(node) {
  const attribute = node.openingElement.attributes.find(
    (attr) => attr.type === "JSXAttribute" && attr.name.name === "aria-label",
  );
  return attribute?.value?.type === "Literal" ? String(attribute.value.value) : null;
}

/** @type {import("eslint").Rule.RuleModule} */
export const linkTextRule = {
  meta: {
    type: "problem",
    docs: { description: "Link text states the fact it links to" },
    messages: {
      vague:
        'Link text "{{text}}" says nothing on its own. Write the fact instead, e.g. "CHAPS reopens Tue 29 Dec 2026".',
    },
    schema: [],
  },
  create(context) {
    return {
      JSXElement(node) {
        const name = elementName(node);
        if (name !== "Link" && name !== "a") {
          return;
        }
        for (const text of [literalText(node), ariaLabel(node)]) {
          if (text !== null && isVagueLinkText(text)) {
            context.report({ node, messageId: "vague", data: { text: text.trim() } });
            return;
          }
        }
      },
    };
  },
};

const plugin = { rules: { "link-text": linkTextRule } };

export default plugin;
