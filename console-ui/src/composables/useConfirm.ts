/** In-app confirm dialog options (theme-aware; replaces window.confirm). */
export type ConfirmOptions = {
  title?: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  /** Styles confirm button as danger (destructive ops). */
  danger?: boolean
}

export type ConfirmFn = (opts: ConfirmOptions | string) => Promise<boolean>
